package br.gov.sus.nexus.core.tasks;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.aps;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import br.gov.sus.nexus.core.support.Envelopes;
import br.gov.sus.nexus.core.support.Fixtures;
import br.gov.sus.nexus.core.support.Outbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tarefas: criação com SLA, máquina de estados, eventos e revisão do MPI por evento. */
@QuarkusTest
class TaskFlowTest {

  @Inject Bus bus;

  @Test
  void createTransitionAndEvents() throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("task_type", "active_search");
    body.put("priority", "medium");
    body.put("title", "Busca ativa gestante");
    body.put("description", "Visita domiciliar");
    body.put("assignee", Map.of("kind", "team", "id", "0000123456"));
    body.put("origin", Map.of("kind", "user", "id", "dra.ana"));
    Response created = aps(TENANT_A).body(body).post("/api/v1/tasks");
    created
        .then()
        .statusCode(201)
        .body("id", startsWith("task_"))
        .body("status", equalTo("assigned"))
        .body("sla_policy_id", equalTo("sla_active_search"))
        .body("due_at", notNullValue())
        .body("overdue", equalTo(false));
    String id = created.path("id");

    aps(TENANT_A)
        .body(Map.of("action", "start"))
        .post("/api/v1/tasks/" + id + "/transition")
        .then()
        .statusCode(200)
        .body("status", equalTo("in_progress"));
    // transição inválida: assign a partir de in_progress
    aps(TENANT_A)
        .body(Map.of("action", "assign", "assignee", Map.of("kind", "user", "id", "x")))
        .post("/api/v1/tasks/" + id + "/transition")
        .then()
        .statusCode(409);
    aps(TENANT_A)
        .body(
            Map.of(
                "action",
                "complete",
                "outcome",
                "contato realizado",
                "reason",
                "cidadã localizada"))
        .post("/api/v1/tasks/" + id + "/transition")
        .then()
        .statusCode(200)
        .body("status", equalTo("completed"))
        .body("outcome", equalTo("contato realizado"));
    aps(TENANT_A)
        .body(Map.of("action", "cancel"))
        .post("/api/v1/tasks/" + id + "/transition")
        .then()
        .statusCode(409);

    aps(TENANT_A)
        .get("/api/v1/tasks?assignee_kind=team&assignee_id=0000123456&status=completed")
        .then()
        .statusCode(200)
        .body("items.find { it.id == '" + id + "' }.status", equalTo("completed"));
    aps(Api.TENANT_B).get("/api/v1/tasks/" + id).then().statusCode(404);

    List<Outbox.Row> rows = Outbox.rowsFor(id);
    assertThat(rows)
        .extracting(Outbox.Row::eventType)
        .containsExactly("sus.task.created", "sus.task.completed");
    Outbox.assertValid(rows, "contracts/events/task/task.v1.schema.json");
    assertThat(rows.get(0).payload().get("data").get("assignee").get("id").asText())
        .isEqualTo("0000123456");
  }

  @Test
  void escalateUsesPolicyTargetAndUrgentPolicyHasShorterSla() {
    Response urgent =
        aps(TENANT_A)
            .body(Map.of("task_type", "generic", "priority", "urgent", "title", "Urgente"))
            .post("/api/v1/tasks");
    urgent
        .then()
        .statusCode(201)
        .body("sla_policy_id", equalTo("sla_generic_urgent"))
        .body("status", equalTo("open"));
    String id = urgent.path("id");
    aps(TENANT_A)
        .body(Map.of("action", "escalate", "reason", "sem resposta da equipe"))
        .post("/api/v1/tasks/" + id + "/transition")
        .then()
        .statusCode(200)
        .body("status", equalTo("escalated"))
        .body("assignee.kind", equalTo("queue"))
        .body("assignee.id", equalTo("coordenacao_aps"));
    aps(TENANT_A)
        .body(Map.of("action", "assign", "assignee", Map.of("kind", "user", "id", "coord.ana")))
        .post("/api/v1/tasks/" + id + "/transition")
        .then()
        .statusCode(200)
        .body("status", equalTo("assigned"));
  }

  @Test
  void mergeCaseOpenedEventCreatesMpiReviewTaskAndDecisionClosesIt() throws Exception {
    String cns = Fixtures.randomProvisionalCns();
    String citizenId =
        integration(TENANT_A)
            .body(Registration.of("Revisão MPI Evento", LocalDate.of(1960, 1, 1)).cns(cns).build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");
    String caseId = Ulid.generate(Ulid.MERGE_CASE);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", "case_opened");
    data.put("case_id", caseId);
    data.put("surviving_citizen_id", citizenId);
    data.put("merged_citizen_ids", List.of("cit_00000000000000000000000001"));
    data.put("evidence_count", 3);
    data.put("reversible", true);
    String payload = Envelopes.build(TENANT_A, "sus.identity.merge.case_opened", data, citizenId);
    bus.send("tasks-merge-in", payload);
    bus.send("tasks-merge-in", payload); // duplicata

    Await.until(
        "tarefa mpi_review criada",
        () ->
            aps(TENANT_A)
                    .get("/api/v1/tasks?task_type=mpi_review&citizen_id=" + citizenId)
                    .jsonPath()
                    .getList("items")
                    .size()
                == 1);
    Response tasks =
        aps(TENANT_A).get("/api/v1/tasks?task_type=mpi_review&citizen_id=" + citizenId);
    tasks
        .then()
        .body("items", hasSize(1))
        .body("items[0].assignee.kind", equalTo("queue"))
        .body("items[0].assignee.id", equalTo("cadastro_mestre"))
        .body("items[0].origin.id", equalTo(caseId))
        .body("items[0].sla_policy_id", equalTo("sla_mpi_review"));
    String taskId = tasks.path("items[0].id");

    data.put("action", "merged");
    data.put("decided_by", "gestor.joao");
    bus.send(
        "tasks-merge-in", Envelopes.build(TENANT_A, "sus.identity.merge.merged", data, citizenId));
    Await.until(
        "tarefa concluída",
        () -> "completed".equals(aps(TENANT_A).get("/api/v1/tasks/" + taskId).path("status")));
    aps(TENANT_A).get("/api/v1/tasks/" + taskId).then().body("outcome", equalTo("merged"));
  }
}
