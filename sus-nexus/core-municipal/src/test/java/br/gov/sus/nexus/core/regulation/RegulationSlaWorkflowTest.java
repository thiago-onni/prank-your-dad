package br.gov.sus.nexus.core.regulation;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.aps;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import br.gov.sus.nexus.core.platform.temporal.TemporalClientProvider;
import br.gov.sus.nexus.core.platform.temporal.TemporalWorkers;
import br.gov.sus.nexus.core.regulation.infrastructure.temporal.RegulationSlaWorkflow;
import br.gov.sus.nexus.core.regulation.infrastructure.temporal.RegulationWorkflowStarter;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import br.gov.sus.nexus.core.support.Outbox;
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
 * {@code RegulationSlaWorkflow} em ambiente Temporal in-process (time-skipping): metade do SLA com
 * pendência documental → tarefa; SLA vencido → pendência {@code sla_breached}, evento e tarefa na
 * fila {@code regulacao}; decisão sinalizada encerra sem estouro.
 */
@QuarkusTest
class RegulationSlaWorkflowTest {

  @Inject TemporalClientProvider provider;
  @Inject TemporalWorkers workers;
  @Inject RegulationWorkflowStarter starter;
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

  private Response register(String priority, String name) {
    String citizenId = RegulationFlowTest.newCitizen(name);
    Map<String, Object> body =
        RegulationFlowTest.request(
            "REG-SLA-" + System.nanoTime(), citizenId, "procedure", "requested", priority);
    body.put("requested_at", OffsetDateTime.now().toString());
    Response created = integration(TENANT_A).body(body).post("/api/v1/regulation/requests");
    created.then().statusCode(201);
    return created;
  }

  @Test
  void slaTimersCreatePendingDocumentTaskAndBreachIssueTaskAndEvent() throws Exception {
    Response created = register("urgent", "SLA Urgente Costa"); // 7 dias
    String id = created.path("id");
    String citizenId = created.path("citizen_id");
    OffsetDateTime requestedAt = OffsetDateTime.parse(created.path("requested_at"));
    OffsetDateTime due = OffsetDateTime.parse(created.path("sla_due_at"));
    assertThat(due).isEqualTo(requestedAt.plusDays(7));

    // o starter é acionado pelo consumidor do evento created (relay do outbox)
    bus.relayAndDeliver();
    Await.until(
        "workflow iniciado pelo evento",
        () -> !starter.startRegulationSla(TENANT_A, id, requestedAt.toInstant(), due.toInstant()));

    // 50%: pendência documental aberta → tarefa regulation_pending_document para a UBS
    env.sleep(Duration.ofDays(4));
    Await.until(
        "tarefa de pendência documental",
        Duration.ofSeconds(30),
        () ->
            !aps(TENANT_A)
                .get("/api/v1/tasks?task_type=regulation_pending_document&citizen_id=" + citizenId)
                .jsonPath()
                .getList("items")
                .isEmpty());
    aps(TENANT_A)
        .get("/api/v1/tasks?task_type=regulation_pending_document&citizen_id=" + citizenId)
        .then()
        .body("items[0].assignee.kind", equalTo("health_unit"))
        .body("items[0].assignee.id", equalTo(RegulationFlowTest.UBS));

    // 100%: estouro
    env.sleep(Duration.ofDays(4));
    Await.until(
        "SLA vencido registrado",
        Duration.ofSeconds(30),
        () ->
            Boolean.TRUE.equals(
                RegulationFlowTest.regulador(TENANT_A)
                    .get("/api/v1/regulation/requests/" + id)
                    .path("sla_breached")));
    RegulationFlowTest.regulador(TENANT_A)
        .get("/api/v1/regulation/requests/" + id)
        .then()
        .body("issues.findAll { it.status == 'open' }.kind", hasItem("sla_breached"))
        .body("issues.find { it.kind == 'sla_breached' }.origin.kind", equalTo("workflow"))
        .body("status", equalTo("requested"));
    RegulationFlowTest.regulador(TENANT_A)
        .get("/api/v1/regulation/requests?issue=sla_breached")
        .then()
        .body("items.id", hasItem(id));
    Await.until(
        "tarefa de SLA na fila regulacao",
        () ->
            !aps(TENANT_A)
                .get("/api/v1/tasks?task_type=generic&citizen_id=" + citizenId)
                .jsonPath()
                .getList("items")
                .isEmpty());
    aps(TENANT_A)
        .get("/api/v1/tasks?task_type=generic&citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(1))
        .body("items[0].title", equalTo("SLA de regulação vencido"))
        .body("items[0].assignee.kind", equalTo("queue"))
        .body("items[0].assignee.id", equalTo("regulacao"))
        .body("items[0].priority", equalTo("urgent"));

    WorkflowStub stub =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(RegulationSlaWorkflow.WORKFLOW_ID_PREFIX + id);
    assertThat(stub.getResult(String.class)).isEqualTo("sla_breached");

    List<Outbox.Row> statusRows =
        Outbox.rowsFor(id).stream()
            .filter(r -> r.eventType().equals("sus.regulation.status.changed"))
            .toList();
    assertThat(statusRows).isNotEmpty();
    Outbox.Row breach = statusRows.get(statusRows.size() - 1);
    assertThat(breach.payload().get("data").get("sla_breached").asBoolean()).isTrue();
    assertThat(breach.payload().get("data").get("actor_kind").asText()).isEqualTo("workflow");
    Outbox.assertValid(statusRows, "contracts/events/regulation/status.v1.schema.json");

    // idempotente: novo estouro não duplica tarefa/pendência
    bus.relayAndDeliver();
    aps(TENANT_A)
        .get("/api/v1/tasks?task_type=generic&citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(1));
  }

  @Test
  void decisionSignalEndsWorkflowWithoutBreach() {
    Response created = register("emergency", "SLA Decidida Rocha"); // 1 dia
    String id = created.path("id");
    String citizenId = created.path("citizen_id");
    OffsetDateTime requestedAt = OffsetDateTime.parse(created.path("requested_at"));
    OffsetDateTime due = OffsetDateTime.parse(created.path("sla_due_at"));
    assertThat(starter.startRegulationSla(TENANT_A, id, requestedAt.toInstant(), due.toInstant()))
        .isTrue();
    assertThat(starter.startRegulationSla(TENANT_A, id, requestedAt.toInstant(), due.toInstant()))
        .isFalse();

    String recordId = created.path("source_record_id");
    RegulationFlowTest.regulador(TENANT_A)
        .body(RegulationFlowTest.status(recordId, "authorized", Map.of("regulator_id", "reg-1")))
        .post("/api/v1/regulation/requests/" + id + "/status")
        .then()
        .statusCode(200);
    bus.relayAndDeliver(); // status.changed → sinal via consumidor
    starter.signalRegulationStatus(id, "authorized"); // sinal direto (idempotente)
    WorkflowStub stub =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(RegulationSlaWorkflow.WORKFLOW_ID_PREFIX + id);
    assertThat(stub.getResult(String.class)).isEqualTo("decided:authorized");
    env.sleep(Duration.ofDays(2));
    RegulationFlowTest.regulador(TENANT_A)
        .get("/api/v1/regulation/requests/" + id)
        .then()
        .body("sla_breached", equalTo(false));
    aps(TENANT_A)
        .get("/api/v1/tasks?task_type=generic&citizen_id=" + citizenId)
        .then()
        .body("items", hasSize(0));
  }
}
