package br.gov.sus.nexus.core.hospital;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.aps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import br.gov.sus.nexus.core.hospital.infrastructure.temporal.DischargeFollowUpWorkflow;
import br.gov.sus.nexus.core.hospital.infrastructure.temporal.HospitalWorkflowStarter;
import br.gov.sus.nexus.core.platform.temporal.TemporalClientProvider;
import br.gov.sus.nexus.core.platform.temporal.TemporalWorkers;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code DischargeFollowUpWorkflow} (Workflow 2) em ambiente Temporal in-process: alta de alto
 * risco → 24 h sem contato → escalonamento (tarefa {@code active_search} + lacuna {@code
 * post_discharge_no_contact}) → segundo prazo → {@code not_found}. Contato registrado encerra o
 * workflow e abre o plano de cuidado.
 */
@QuarkusTest
class DischargeFollowUpWorkflowTest {

  @Inject TemporalClientProvider provider;
  @Inject TemporalWorkers workers;
  @Inject HospitalWorkflowStarter starter;
  @Inject Bus bus;

  TestWorkflowEnvironment env;

  @BeforeEach
  void startTestEnvironment() {
    env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker(provider.taskQueue());
    workers.register(worker);
    env.start();
    provider.useClient(env.getWorkflowClient());
  }

  @AfterEach
  void stopTestEnvironment() {
    provider.useClient(null);
    env.close();
  }

  private String taskOf(String citizenId, String type) {
    Await.until(
        "tarefa " + type,
        Duration.ofSeconds(30),
        () ->
            !aps(TENANT_A)
                .get("/api/v1/tasks?task_type=" + type + "&citizen_id=" + citizenId)
                .jsonPath()
                .getList("items")
                .isEmpty());
    return aps(TENANT_A)
        .get("/api/v1/tasks?task_type=" + type + "&citizen_id=" + citizenId)
        .path("items[0].id");
  }

  @Test
  void noContactEscalatesOpensGapAndClosesAsNotFound() {
    String citizenId = HospitalFlowTest.newCitizen("Pós-alta Sem Contato");
    String recordId = "HEP-WF-" + System.nanoTime();
    Response discharged =
        HospitalFlowTest.admitAndDischarge(citizenId, recordId, 8, null, List.of("diabetes"));
    String id = discharged.path("id");
    OffsetDateTime dueAt = OffsetDateTime.parse(discharged.path("followup.due_at"));
    assertThat(discharged.<String>path("risk_level")).isEqualTo("high");

    bus.relayAndDeliver(); // sus.hospital.discharge.completed → starter (consumidor)
    Await.until(
        "workflow iniciado",
        () -> !starter.startDischargeFollowUp(TENANT_A, id, dueAt.toInstant(), "high"));

    // 1) 24 h sem contato → escalonado: busca ativa para a microárea + lacuna pós-alta
    env.sleep(Duration.ofHours(25));
    String activeSearch = taskOf(citizenId, "active_search");
    aps(TENANT_A)
        .get("/api/v1/tasks/" + activeSearch)
        .then()
        .body("priority", equalTo("high"))
        .body("assignee.kind", equalTo("team"))
        .body("assignee.id", equalTo(HospitalFlowTest.INE))
        .body("origin.id", equalTo("discharge-followup:" + id + ":active_search"))
        .body("title", org.hamcrest.Matchers.containsString("microárea 02"));
    aps(TENANT_A)
        .get("/api/v1/hospital/episodes/" + id)
        .then()
        .body("followup.status", equalTo("escalated"));
    Response gaps =
        aps(TENANT_A)
            .get(
                "/api/v1/caregaps?gap_kind=post_discharge_no_contact&team_ine="
                    + HospitalFlowTest.INE);
    gaps.then().statusCode(200);
    Map<String, Object> gap =
        gaps.jsonPath().<Map<String, Object>>getList("items").stream()
            .filter(g -> citizenId.equals(g.get("citizen_id")))
            .findFirst()
            .orElseThrow();
    assertThat(gap.get("status")).isEqualTo("open");
    assertThat(gap.get("care_line")).isEqualTo("diabetes");
    assertThat(gap.get("contact_valid")).isEqualTo(true);
    assertThat(gap.get("task_id").toString()).startsWith("task_");
    aps(TENANT_A)
        .get("/api/v1/citizens/" + citizenId + "/summary")
        .then()
        .body("care_gaps", equalTo(1));

    // 2) segundo prazo (escalate_after = 12 h) sem contato → not_found
    env.sleep(Duration.ofHours(13));
    WorkflowStub stub =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(DischargeFollowUpWorkflow.WORKFLOW_ID_PREFIX + id);
    assertThat(stub.getResult(String.class)).isEqualTo("not_found");
    aps(TENANT_A)
        .get("/api/v1/hospital/episodes/" + id)
        .then()
        .body("followup.status", equalTo("closed"))
        .body("followup.outcome", equalTo("not_found"))
        .body("followup.care_plan_id", org.hamcrest.Matchers.nullValue());
    aps(TENANT_A)
        .get("/api/v1/tasks/" + activeSearch)
        .then()
        .body("status", equalTo("completed"))
        .body("outcome", equalTo("not_found"));
    aps(TENANT_A)
        .get("/api/v1/caregaps?status=resolved&gap_kind=post_discharge_no_contact")
        .then()
        .body(
            "items.find { it.citizen_id == '" + citizenId + "' }.resolution", equalTo("not_found"));
    aps(TENANT_A)
        .get("/api/v1/citizens/" + citizenId + "/summary")
        .then()
        .body("care_gaps", equalTo(0));
    aps(TENANT_A)
        .get("/api/v1/hospital/episodes?followup_status=closed&citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(1));
  }

  @Test
  void contactRegisteredEndsWorkflowAndOpensCarePlan() {
    String citizenId = HospitalFlowTest.newCitizen("Pós-alta Contato Feito");
    String recordId = "HEP-WF-C-" + System.nanoTime();
    Response discharged =
        HospitalFlowTest.admitAndDischarge(citizenId, recordId, 2, null, List.of("diabetes"));
    String id = discharged.path("id");
    OffsetDateTime dueAt = OffsetDateTime.parse(discharged.path("followup.due_at"));
    assertThat(discharged.<String>path("risk_level")).isEqualTo("low");
    assertThat(starter.startDischargeFollowUp(TENANT_A, id, dueAt.toInstant(), "low")).isTrue();
    assertThat(starter.startDischargeFollowUp(TENANT_A, id, dueAt.toInstant(), "low")).isFalse();

    env.sleep(Duration.ofDays(2));
    aps(TENANT_A)
        .get("/api/v1/hospital/episodes/" + id)
        .then()
        .body("followup.status", equalTo("pending"));
    aps(TENANT_A)
        .body(Map.of("outcome", "appointment_scheduled", "note", "consulta marcada na UBS"))
        .post("/api/v1/hospital/episodes/" + id + "/followup")
        .then()
        .statusCode(200)
        .body("followup.status", equalTo("scheduled"))
        .body("followup.care_plan_id", startsWith("cp_"));
    WorkflowStub stub =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(DischargeFollowUpWorkflow.WORKFLOW_ID_PREFIX + id);
    assertThat(stub.getResult(String.class)).isEqualTo("contacted:appointment_scheduled");
    env.sleep(Duration.ofDays(10));
    aps(TENANT_A)
        .get("/api/v1/tasks?task_type=active_search&citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(0));
    aps(TENANT_A)
        .get("/api/v1/careplans?citizen_id=" + citizenId + "&care_line=diabetes&status=active")
        .then()
        .body("items", hasSize(1))
        .body("items[0].origin.kind", equalTo("hospital_discharge"))
        .body("items[0].start_at", notNullValue());
  }
}
