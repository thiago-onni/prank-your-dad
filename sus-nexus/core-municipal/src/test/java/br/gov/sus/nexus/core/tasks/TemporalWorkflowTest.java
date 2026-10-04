package br.gov.sus.nexus.core.tasks;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.aps;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.support.Api.Registration;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Outbox;
import br.gov.sus.nexus.core.tasks.infrastructure.temporal.MpiReviewWorkflow;
import br.gov.sus.nexus.core.tasks.infrastructure.temporal.TaskSlaWorkflow;
import br.gov.sus.nexus.core.tasks.infrastructure.temporal.TaskWorkflowStarter;
import br.gov.sus.nexus.core.tasks.infrastructure.temporal.TemporalClientProvider;
import br.gov.sus.nexus.core.tasks.infrastructure.temporal.TemporalWorkers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Workflows Temporal em ambiente de teste in-process (time-skipping): activities reais (CDI) contra
 * o PostgreSQL local; o cliente de teste é injetado no {@link TemporalClientProvider}.
 */
@QuarkusTest
class TemporalWorkflowTest {

  @Inject TemporalClientProvider provider;
  @Inject TemporalWorkers workers;
  @Inject TaskWorkflowStarter starter;

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

  @Test
  void taskSlaWorkflowBreachesAndEscalatesWhenDueDatePasses() throws Exception {
    OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
    Response created =
        aps(TENANT_A)
            .body(
                Map.of(
                    "task_type", "active_search",
                    "priority", "medium",
                    "title", "SLA via Temporal",
                    "due_at", due.toString()))
            .post("/api/v1/tasks");
    created.then().statusCode(201);
    String id = created.path("id");

    assertThat(starter.startTaskSla(TENANT_A, id, due.toInstant(), "sla_active_search")).isTrue();
    // replay do evento created: workflowId determinístico impede duplicação
    assertThat(starter.startTaskSla(TENANT_A, id, due.toInstant(), "sla_active_search")).isFalse();

    env.sleep(Duration.ofHours(2));
    Await.until(
        "tarefa escalonada por SLA",
        Duration.ofSeconds(30),
        () -> "escalated".equals(aps(TENANT_A).get("/api/v1/tasks/" + id).path("status")));
    aps(TENANT_A)
        .get("/api/v1/tasks/" + id)
        .then()
        .body("sla_breached_at", notNullValue())
        .body("assignee.kind", equalTo("queue"))
        .body("assignee.id", equalTo("coordenacao_aps"));

    WorkflowStub stub =
        env.getWorkflowClient().newUntypedWorkflowStub(TaskSlaWorkflow.WORKFLOW_ID_PREFIX + id);
    assertThat(stub.getResult(String.class)).isEqualTo("sla_breached");

    List<Outbox.Row> rows = Outbox.rowsFor(id);
    assertThat(rows)
        .extracting(Outbox.Row::eventType)
        .containsExactly("sus.task.created", "sus.task.sla_breached", "sus.task.escalated");
    Outbox.assertValid(rows, "contracts/events/task/task.v1.schema.json");
    assertThat(rows.get(2).payload().get("trace").get("causation_id").asText())
        .isEqualTo(rows.get(1).id());
  }

  @Test
  void completedSignalEndsTaskSlaWorkflowWithoutBreach() {
    OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
    String id =
        aps(TENANT_A)
            .body(
                Map.of(
                    "task_type", "generic",
                    "priority", "low",
                    "title", "Concluída antes do SLA",
                    "due_at", due.toString()))
            .post("/api/v1/tasks")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    starter.startTaskSla(TENANT_A, id, due.toInstant(), null);
    aps(TENANT_A)
        .body(Map.of("action", "complete", "outcome", "ok"))
        .post("/api/v1/tasks/" + id + "/transition")
        .then()
        .statusCode(200);
    starter.signalTask(id, "completed");
    WorkflowStub stub =
        env.getWorkflowClient().newUntypedWorkflowStub(TaskSlaWorkflow.WORKFLOW_ID_PREFIX + id);
    assertThat(stub.getResult(String.class)).isEqualTo("completed");
    env.sleep(Duration.ofHours(2));
    aps(TENANT_A).get("/api/v1/tasks/" + id).then().body("status", equalTo("completed"));
    // sinal para workflow já encerrado é ignorado
    starter.signalTask(id, "cancelled");
  }

  @Test
  void mpiReviewWorkflowEnsuresTaskEscalatesOnSlaAndClosesOnDecision() {
    String citizenId =
        integration(TENANT_A)
            .body(Registration.of("Revisão Temporal", LocalDate.of(1975, 3, 3)).build())
            .post("/api/v1/citizens")
            .then()
            .statusCode(201)
            .extract()
            .path("municipal_citizen_id");
    String caseId = Ulid.generate(Ulid.MERGE_CASE);
    assertThat(starter.startMpiReview(TENANT_A, caseId, citizenId, Duration.ofHours(1))).isTrue();

    Await.until(
        "tarefa mpi_review garantida pelo workflow",
        Duration.ofSeconds(30),
        () ->
            aps(TENANT_A)
                    .get("/api/v1/tasks?task_type=mpi_review&citizen_id=" + citizenId)
                    .jsonPath()
                    .getList("items")
                    .size()
                == 1);
    String taskId =
        aps(TENANT_A)
            .get("/api/v1/tasks?task_type=mpi_review&citizen_id=" + citizenId)
            .path("items[0].id");

    env.sleep(Duration.ofHours(2));
    Await.until(
        "revisão escalonada",
        Duration.ofSeconds(30),
        () -> "escalated".equals(aps(TENANT_A).get("/api/v1/tasks/" + taskId).path("status")));

    starter.signalMpiDecided(caseId, "rejected");
    WorkflowStub stub =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(MpiReviewWorkflow.WORKFLOW_ID_PREFIX + caseId);
    assertThat(stub.getResult(String.class)).isEqualTo("rejected");
    Await.until(
        "tarefa concluída pela decisão",
        Duration.ofSeconds(30),
        () -> "completed".equals(aps(TENANT_A).get("/api/v1/tasks/" + taskId).path("status")));
    aps(TENANT_A).get("/api/v1/tasks/" + taskId).then().body("outcome", equalTo("rejected"));
  }
}
