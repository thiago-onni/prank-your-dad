package br.gov.sus.nexus.core.integration;

import static br.gov.sus.nexus.core.support.Api.TENANT_A;
import static br.gov.sus.nexus.core.support.Api.TENANT_B;
import static br.gov.sus.nexus.core.support.Api.integration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.support.Api;
import br.gov.sus.nexus.core.support.Await;
import br.gov.sus.nexus.core.support.Bus;
import br.gov.sus.nexus.core.support.Envelopes;
import br.gov.sus.nexus.core.support.Outbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Registry de conectores, ledger espelho, DLQ, reconciliação, reprocessamento e consumo de status.
 */
@QuarkusTest
class IntegrationFlowTest {

  @Inject Bus bus;

  @Test
  void connectorLedgerDlqAndReprocessFlow() throws Exception {
    String connectorId = "connector-pec-" + System.nanoTime();
    integration(TENANT_A)
        .body(
            Map.of(
                "connector_version", "1.2.0",
                "source_system", "ESUS_APS_PEC",
                "health", "healthy",
                "descriptor", Map.of("supported_entities", List.of("citizen", "appointment"))))
        .post("/api/v1/integration/connectors/" + connectorId + "/heartbeat")
        .then()
        .statusCode(200)
        .body("health", equalTo("healthy"))
        .body("connector_version", equalTo("1.2.0"));

    String ok = Ulid.generate(Ulid.INTEGRATION_MESSAGE);
    String failed = Ulid.generate(Ulid.INTEGRATION_MESSAGE);
    integration(TENANT_A)
        .body(message(ok, connectorId, "processed", null))
        .post("/api/v1/integration/messages")
        .then()
        .statusCode(200)
        .body("id", equalTo(ok))
        .body("status", equalTo("processed"));
    Map<String, Object> dead = message(failed, connectorId, "dead_lettered", "validation");
    dead.put(
        "dead_letter",
        Map.of(
            "topic",
            "sus.ingest.pec.v1",
            "reason",
            "CNS inválido",
            "stage",
            "validate",
            "owner",
            "integracao"));
    integration(TENANT_A)
        .body(dead)
        .post("/api/v1/integration/messages")
        .then()
        .statusCode(200)
        .body("status", equalTo("dead_lettered"))
        .body("last_error.code", equalTo("E-validation"))
        .body("attempts", equalTo(3));

    Response connectors = integration(TENANT_A).get("/api/v1/integration/connectors");
    connectors.then().statusCode(200);
    List<Map<String, Object>> list = connectors.jsonPath().getList("$");
    Map<String, Object> mine =
        list.stream()
            .filter(c -> connectorId.equals(c.get("connector_id")))
            .findFirst()
            .orElseThrow();
    assertThat(((Number) mine.get("received_24h")).longValue()).isEqualTo(2);
    assertThat(((Number) mine.get("failed_24h")).longValue()).isEqualTo(1);
    assertThat(((Number) mine.get("dlq_open")).longValue()).isEqualTo(1);
    assertThat(mine.get("last_message_at")).isNotNull();

    integration(TENANT_A)
        .get("/api/v1/integration/messages?connector_id=" + connectorId + "&status=dead_lettered")
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].id", equalTo(failed))
        .body("items[0].raw_ref", equalTo("s3://raw/" + failed));
    integration(TENANT_A)
        .get("/api/v1/integration/dlq?connector_id=" + connectorId)
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].message_id", equalTo(failed))
        .body("items[0].reason", equalTo("CNS inválido"));

    // outro tenant não vê nada (RLS)
    integration(TENANT_B)
        .get("/api/v1/integration/messages?connector_id=" + connectorId)
        .then()
        .statusCode(200)
        .body("items", hasSize(0));

    Response reprocess =
        integration(TENANT_A)
            .body(Map.of("reason", "corrigido na origem, reprocessar"))
            .post("/api/v1/integration/messages/" + failed + "/reprocess");
    reprocess.then().statusCode(202).body("event_id", notNullValue());
    integration(TENANT_A)
        .get("/api/v1/integration/messages/" + failed)
        .then()
        .statusCode(200)
        .body("status", equalTo("reprocessing"));
    integration(TENANT_A)
        .get("/api/v1/integration/dlq?connector_id=" + connectorId)
        .then()
        .statusCode(200)
        .body("items[0].triaged_at", notNullValue());

    List<Outbox.Row> rows = Outbox.rowsFor(failed);
    assertThat(rows)
        .extracting(Outbox.Row::eventType)
        .containsExactly("sus.integration.reprocess.requested");
    Outbox.assertValid(rows, "contracts/events/integration/reprocess.v1.schema.json");
    assertThat(rows.get(0).payload().get("data").get("suppress_external_effects").asBoolean())
        .isTrue();

    // mensagem já processada não pode ser reprocessada duas vezes em sequência (status
    // reprocessing)
    integration(TENANT_A)
        .post("/api/v1/integration/messages/" + failed + "/reprocess")
        .then()
        .statusCode(409);

    integration(TENANT_A)
        .body(
            Map.of(
                "connector_id",
                connectorId,
                "entity_type",
                "citizen",
                "period_start",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString(),
                "period_end",
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                "source_count",
                100,
                "bus_count",
                98,
                "gap",
                2))
        .post("/api/v1/integration/reconciliation")
        .then()
        .statusCode(201);
    integration(TENANT_A)
        .get("/api/v1/integration/reconciliation?connector_id=" + connectorId)
        .then()
        .statusCode(200)
        .body("items[0].gap", equalTo(2));
    integration(TENANT_A)
        .get("/api/v1/integration/connectors")
        .then()
        .statusCode(200)
        .body("find { it.connector_id == '" + connectorId + "' }.reconciliation_gap", equalTo(2));
  }

  @Test
  void statusEventsFromKafkaUpdateTheRegistryIdempotently() throws Exception {
    String connectorId = "connector-cnes-" + System.nanoTime();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", "degraded");
    data.put("connector_id", connectorId);
    data.put("connector_version", "0.9.1");
    data.put("source_system", "CNES");
    data.put("detail", "latência alta na fonte");
    data.put("metrics", Map.of("received_total", 10, "failed_total", 2, "latency_p95_ms", 950.5));
    String eventId = Ulid.generate(Ulid.EVENT);
    String payload =
        Envelopes.build(TENANT_A, "sus.integration.status.degraded", data, null, eventId, null);
    assertThat(
            Outbox.schema("contracts/events/integration/status.v1.schema.json")
                .validate(Outbox.MAPPER.readTree(payload).get("data")))
        .isEmpty();

    bus.send("integration-status-in", payload);
    Await.until(
        "registry atualizado",
        () ->
            integration(TENANT_A)
                .get("/api/v1/integration/connectors")
                .jsonPath()
                .getList("findAll { it.connector_id == '" + connectorId + "' }.health")
                .contains("degraded"));

    // duplicata (mesmo event_id) e depois "down" com outro id
    bus.send("integration-status-in", payload);
    data.put("action", "down");
    bus.send(
        "integration-status-in",
        Envelopes.build(TENANT_A, "sus.integration.status.down", data, null));
    Await.until(
        "health=down",
        () ->
            integration(TENANT_A)
                .get("/api/v1/integration/connectors")
                .jsonPath()
                .getList("findAll { it.connector_id == '" + connectorId + "' }.health")
                .contains("down"));

    try (var c = Api.adminConnection();
        var ps =
            c.prepareStatement(
                "select count(*) from platform.event_inbox where event_id = ? and consumer_group = ?")) {
      ps.setString(1, eventId);
      ps.setString(2, "core-integration-status");
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).isEqualTo(1);
      }
    }

    // reconciliation_gap gera entrada de reconciliação
    Map<String, Object> gap = new LinkedHashMap<>();
    gap.put("action", "reconciliation_gap");
    gap.put("connector_id", connectorId);
    gap.put("connector_version", "0.9.1");
    gap.put(
        "metrics", Map.of("received_total", 50, "processed_total", 47, "reconciliation_gap", 3));
    gap.put(
        "period",
        Map.of(
            "start", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString(),
            "end", OffsetDateTime.now(ZoneOffset.UTC).toString()));
    bus.send(
        "integration-status-in",
        Envelopes.build(TENANT_A, "sus.integration.status.reconciliation_gap", gap, null));
    Await.until(
        "reconciliação registrada",
        () ->
            integration(TENANT_A)
                    .get("/api/v1/integration/reconciliation?connector_id=" + connectorId)
                    .jsonPath()
                    .getList("items")
                    .size()
                >= 1);
    integration(TENANT_A)
        .get("/api/v1/integration/reconciliation?connector_id=" + connectorId)
        .then()
        .body("items[0].gap", equalTo(3))
        .body("items[0].source_count", greaterThanOrEqualTo(50));
  }

  static Map<String, Object> message(
      String id, String connectorId, String status, String errorStage) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("connector_id", connectorId);
    m.put("source_system", "ESUS_APS_PEC");
    m.put("source_record_id", "PEC-" + id.substring(4, 10));
    m.put("entity_type", "citizen");
    m.put("status", status);
    m.put("raw_ref", "s3://raw/" + id);
    m.put("raw_sha256", "a".repeat(64));
    m.put("correlation_id", "corr_" + id.toLowerCase());
    m.put("received_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    if (errorStage != null) {
      m.put("attempts", 3);
      m.put(
          "last_error",
          Map.of(
              "code",
              "E-" + errorStage,
              "message",
              "falha em " + errorStage,
              "stage",
              errorStage,
              "occurred_at",
              OffsetDateTime.now(ZoneOffset.UTC).toString()));
    }
    return m;
  }
}
