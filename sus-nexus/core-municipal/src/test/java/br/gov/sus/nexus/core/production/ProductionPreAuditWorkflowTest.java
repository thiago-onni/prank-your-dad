package br.gov.sus.nexus.core.production;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import br.gov.sus.nexus.core.platform.temporal.TemporalClientProvider;
import br.gov.sus.nexus.core.platform.temporal.TemporalWorkers;
import br.gov.sus.nexus.core.production.infrastructure.temporal.ProductionPreAuditWorkflow;
import br.gov.sus.nexus.core.production.infrastructure.temporal.ProductionWorkflowStarter;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code ProductionPreAuditWorkflow} (Workflow 3) em Temporal in-process com time-skipping:
 * registro pendente sem correção → no prazo da competência vira pendência {@code deadline_missed};
 * correção humana sinaliza {@code corrected}, revalida e encerra o workflow como {@code validated}.
 */
@QuarkusTest
class ProductionPreAuditWorkflowTest {

  @Inject TemporalClientProvider provider;
  @Inject TemporalWorkers workers;
  @Inject ProductionWorkflowStarter starter;
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

  private static Response pendingRecord(String cnes) {
    ProductionFlowTest.Citizen c =
        ProductionFlowTest.newCitizen(TENANT_A, "female", LocalDate.of(1982, 4, 4));
    Response r =
        ProductionFlowTest.post(
            ProductionFlowTest.record(
                "PROD-WF-" + System.nanoTime(), "bpa_i", cnes, "223505", "0301010064", 1, c));
    r.then().statusCode(201).body("status", equalTo("pending"));
    return r;
  }

  @Test
  void pendingRecordExpiresAtCompetenceDeadline() {
    String cnes = ProductionFlowTest.newUnit(TENANT_A);
    Response pending = pendingRecord(cnes);
    String id = pending.path("id");
    Instant deadline = OffsetDateTime.parse(pending.path("deadline_at")).toInstant();

    bus.relayAndDeliver(); // sus.production.record.created → starter (consumidor)
    Await.until("workflow iniciado", () -> !starter.startPreAudit(TENANT_A, id, deadline));

    // antes do prazo: continua aguardando correção
    env.sleep(Duration.between(Instant.now(), deadline).minus(Duration.ofDays(1)));
    ProductionFlowTest.auditor(TENANT_A)
        .get("/api/v1/production/records/" + id)
        .then()
        .body("status", equalTo("pending"))
        .body("issues.rule_id", org.hamcrest.Matchers.not(hasItem("deadline_missed")));

    // prazo vencido sem correção → deadline_missed (origem workflow)
    env.sleep(Duration.ofDays(2));
    WorkflowStub stub =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(ProductionPreAuditWorkflow.WORKFLOW_ID_PREFIX + id);
    assertThat(stub.getResult(String.class)).isEqualTo("expired:pending");
    ProductionFlowTest.auditor(TENANT_A)
        .get("/api/v1/production/records/" + id)
        .then()
        .body("status", equalTo("pending"))
        .body("issues.find { it.rule_id == 'deadline_missed' }.status", equalTo("open"))
        .body("issues.find { it.rule_id == 'deadline_missed' }.origin", equalTo("workflow"))
        .body("issues.find { it.rule_id == 'deadline_missed' }.severity", equalTo("error"))
        .body(
            "issues.find { it.rule_id == 'deadline_missed' }.rule_version",
            equalTo(ProductionFlowTest.RULE_VERSION))
        .body("history.action", hasItem("deadline_missed"));
  }

  @Test
  void correctionSignalsRevalidationAndEndsWorkflow() {
    String cnes = ProductionFlowTest.newUnit(TENANT_A);
    Response pending = pendingRecord(cnes);
    String id = pending.path("id");
    Instant deadline = OffsetDateTime.parse(pending.path("deadline_at")).toInstant();
    assertThat(starter.startPreAudit(TENANT_A, id, deadline)).isTrue();
    assertThat(starter.startPreAudit(TENANT_A, id, deadline)).isFalse(); // REJECT_DUPLICATE

    env.sleep(Duration.ofDays(3));
    ProductionFlowTest.auditor(TENANT_A)
        .body(
            Map.of(
                "justification",
                "CBO corrigido conforme escala do médico da ESF",
                "changes",
                Map.of("professional_cbo", "225142")))
        .post("/api/v1/production/records/" + id + "/corrections")
        .then()
        .statusCode(200)
        .body("status", equalTo("validated"));
    WorkflowStub stub =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(ProductionPreAuditWorkflow.WORKFLOW_ID_PREFIX + id);
    assertThat(stub.getResult(String.class)).isEqualTo("validated");
    ProductionFlowTest.auditor(TENANT_A)
        .get("/api/v1/production/records/" + id)
        .then()
        .body("issues.rule_id", org.hamcrest.Matchers.not(hasItem("deadline_missed")));
  }

  @Test
  void validRecordWorkflowEndsImmediately() {
    String cnes = ProductionFlowTest.newUnit(TENANT_A);
    ProductionFlowTest.Citizen c =
        ProductionFlowTest.newCitizen(TENANT_A, "male", LocalDate.of(1979, 9, 9));
    Response ok =
        ProductionFlowTest.post(
            ProductionFlowTest.record(
                "PROD-WF-OK-" + System.nanoTime(), "bpa_i", cnes, "225142", "0301010064", 1, c));
    ok.then().statusCode(201).body("status", equalTo("validated"));
    String id = ok.path("id");
    assertThat(
            starter.startPreAudit(
                TENANT_A, id, OffsetDateTime.parse(ok.path("deadline_at")).toInstant()))
        .isTrue();
    WorkflowStub stub =
        env.getWorkflowClient()
            .newUntypedWorkflowStub(ProductionPreAuditWorkflow.WORKFLOW_ID_PREFIX + id);
    assertThat(stub.getResult(String.class)).isEqualTo("validated");
  }
}
