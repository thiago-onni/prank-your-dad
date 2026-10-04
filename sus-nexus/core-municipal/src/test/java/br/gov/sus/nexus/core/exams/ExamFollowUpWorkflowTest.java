package br.gov.sus.nexus.core.exams;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.aps;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import br.gov.sus.nexus.core.exams.infrastructure.temporal.ExamFollowUpWorkflow;
import br.gov.sus.nexus.core.exams.infrastructure.temporal.ExamWorkflowStarter;
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
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code ExamFollowUpWorkflow} (Workflow 1) em ambiente Temporal in-process: N dias sem agendamento
 * → tarefa {@code exam_not_scheduled}; falta → {@code no_show_recovery}; realizado sem laudo →
 * {@code result_pending}; laudo sem retorno em M dias → {@code exam_result_followup}; conclusão da
 * tarefa encerra. Cancelamento encerra.
 */
@QuarkusTest
class ExamFollowUpWorkflowTest {

  @Inject TemporalClientProvider provider;
  @Inject TemporalWorkers workers;
  @Inject ExamWorkflowStarter starter;
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

  private Response newOrder(String name, String recordId) {
    String citizenId = ExamFlowTest.newCitizen(name);
    Response created =
        integration(TENANT_A)
            .body(ExamFlowTest.order(recordId, citizenId, OffsetDateTime.now(ZoneOffset.UTC)))
            .post("/api/v1/exams/orders");
    created.then().statusCode(201);
    return created;
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
  void followUpCreatesTasksAlongTheCycleAndEndsWhenFollowupCompleted() {
    String recordId = "EXO-WF-" + System.nanoTime();
    Response created = newOrder("Exame Workflow Melo", recordId);
    String id = created.path("id");
    String citizenId = created.path("citizen_id");
    OffsetDateTime requestedAt = OffsetDateTime.parse(created.path("requested_at"));

    bus.relayAndDeliver(); // sus.exam.order.created → starter (consumidor)
    Await.until(
        "workflow iniciado",
        () -> !starter.startExamFollowUp(TENANT_A, id, requestedAt.toInstant()));

    // 1) N dias sem agendamento → pendência + tarefa para a UBS solicitante
    env.sleep(Duration.ofDays(16));
    String notScheduled = taskOf(citizenId, "exam_not_scheduled");
    aps(TENANT_A)
        .get("/api/v1/tasks/" + notScheduled)
        .then()
        .body("assignee.kind", equalTo("health_unit"))
        .body("assignee.id", equalTo(ExamFlowTest.UBS));
    aps(TENANT_A).get("/api/v1/exams/orders/" + id).then().body("issues", hasItem("not_scheduled"));

    // 2) agendado (status pela API → evento → sinal); falta → busca ativa; realizado
    integration(TENANT_A)
        .body(
            ExamFlowTest.status(
                recordId,
                "scheduled",
                Map.of("scheduled_at", OffsetDateTime.now(ZoneOffset.UTC).toString())))
        .post("/api/v1/exams/orders/" + id + "/status")
        .then()
        .statusCode(200);
    bus.relayAndDeliver();
    starter.signalStatus(id, "scheduled");
    starter.signalNoShow(id);
    String noShow = taskOf(citizenId, "no_show_recovery");
    aps(TENANT_A).get("/api/v1/tasks/" + noShow).then().body("origin.kind", equalTo("workflow"));
    integration(TENANT_A)
        .body(
            ExamFlowTest.status(recordId, "performed", Map.of("performer_cnes", ExamFlowTest.LAB)))
        .post("/api/v1/exams/orders/" + id + "/status")
        .then()
        .statusCode(200);
    bus.relayAndDeliver();
    starter.signalStatus(id, "performed");

    // 3) realizado sem laudo no prazo → result_pending
    env.sleep(Duration.ofDays(8));
    Await.until(
        "result_pending",
        Duration.ofSeconds(30),
        () ->
            aps(TENANT_A)
                .get("/api/v1/exams/orders/" + id)
                .jsonPath()
                .getList("issues", String.class)
                .contains("result_pending"));

    // laudo registrado → evento result.available sinaliza (via consumidor) — e sinal direto
    integration(TENANT_A)
        .body(ExamFlowTest.result(recordId, false, "s3://laudos/wf.pdf"))
        .post("/api/v1/exams/orders/" + id + "/results")
        .then()
        .statusCode(201);
    bus.relayAndDeliver();
    starter.signalStatus(id, "reported");

    // 4) M dias sem retorno → exam_result_followup; conclusão encerra o workflow
    env.sleep(Duration.ofDays(11));
    String followup = taskOf(citizenId, "exam_result_followup");
    aps(TENANT_A)
        .get("/api/v1/tasks/" + followup)
        .then()
        .body("priority", equalTo("medium"))
        .body("assignee.kind", equalTo("user"))
        .body("assignee.id", equalTo("prof_42"));
    aps(TENANT_A)
        .get("/api/v1/exams/orders/" + id)
        .then()
        .body("issues", hasItem("no_result_followup"));
    aps(TENANT_A)
        .body(Map.of("action", "complete", "outcome", "retorno realizado"))
        .post("/api/v1/tasks/" + followup + "/transition")
        .then()
        .statusCode(200);
    bus.relayAndDeliver(); // sus.task.completed (origem exam-followup:<id>) → sinal
    starter.signalFollowupCompleted(id);
    WorkflowStub stub =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(ExamFollowUpWorkflow.WORKFLOW_ID_PREFIX + id);
    assertThat(stub.getResult(String.class)).isEqualTo("followup_completed");
    aps(TENANT_A)
        .get("/api/v1/exams/orders/" + id)
        .then()
        .body("cycle_times.report_to_followup", notNullValue())
        .body("results[0].followup_task_id", equalTo(followup));
  }

  @Test
  void cancellationEndsWorkflowWithoutTasks() {
    String recordId = "EXO-WF-C-" + System.nanoTime();
    Response created = newOrder("Exame Cancelado Reis", recordId);
    String id = created.path("id");
    String citizenId = created.path("citizen_id");
    OffsetDateTime requestedAt = OffsetDateTime.parse(created.path("requested_at"));
    assertThat(starter.startExamFollowUp(TENANT_A, id, requestedAt.toInstant())).isTrue();
    assertThat(starter.startExamFollowUp(TENANT_A, id, requestedAt.toInstant())).isFalse();

    integration(TENANT_A)
        .body(ExamFlowTest.status(recordId, "cancelled", Map.of("reason", "pedido duplicado")))
        .post("/api/v1/exams/orders/" + id + "/status")
        .then()
        .statusCode(200)
        .body("status", equalTo("cancelled"));
    bus.relayAndDeliver();
    starter.signalStatus(id, "cancelled");
    WorkflowStub stub =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(ExamFollowUpWorkflow.WORKFLOW_ID_PREFIX + id);
    assertThat(stub.getResult(String.class)).isEqualTo("closed:cancelled");
    env.sleep(Duration.ofDays(20));
    aps(TENANT_A).get("/api/v1/tasks?citizen_id=" + citizenId).then().body("items", hasSize(0));
  }
}
