package br.gov.sus.nexus.connectors.sdk.runtime;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.events.IngestEnvelopes;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import br.gov.sus.nexus.connectors.sdk.reprocess.ReprocessCommandHandler;
import br.gov.sus.nexus.connectors.sdk.reprocess.ReprocessResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Comando {@code sus.integration.reprocess.requested} tratado genericamente pelo SDK. */
@QuarkusTest
@QuarkusTestResource(WireMockCoreResource.class)
class ReprocessCommandTest {

  @Inject ProducerTemplate producer;
  @Inject IntegrationMessageLedger ledger;
  @Inject ReprocessCommandHandler handler;
  @Inject ConnectorMetrics metrics;
  @Inject ObjectMapper mapper;

  private WireMockServer core;
  private static int seq;

  @BeforeEach
  void reset() {
    core = WireMockCoreResource.server;
    core.resetAll();
    ((InMemoryIntegrationMessageLedger) ledger).clear();
    handler.reset();
    core.stubFor(
        post(urlPathMatching("/api/v1/integration/.*"))
            .willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")));
  }

  private IntegrationMessage deadLettered(String id) {
    core.stubFor(post(urlEqualTo("/api/v1/citizens")).willReturn(aResponse().withStatus(503)));
    producer.sendBody(
        ConnectorRuntime.INGEST,
        new RawMessage(
            id,
            "v1",
            "citizen",
            "text/csv",
            (id + ";Rita;01012000;F;").getBytes(),
            Map.of("origem", "teste"),
            Instant.now()));
    IntegrationMessage m = ((InMemoryIntegrationMessageLedger) ledger).all().get(0);
    assertThat(m.status()).isEqualTo(IntegrationMessageStatus.DEAD_LETTERED);
    return m;
  }

  private void coreUp() {
    core.stubFor(
        post(urlEqualTo("/api/v1/citizens"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"municipal_citizen_id\":\"cit_01HZZZZZZZZZZZZZZZZZZZZZZZ\",\"classification\":\"new\",\"method\":\"deterministic\"}")));
  }

  private String command(String messageId, String connectorId, String tenant, String rawRef)
      throws Exception {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", "requested");
    data.put("message_id", messageId);
    data.put("connector_id", connectorId);
    data.put("source_system", "TESTE");
    data.put("source_record_id", "77");
    if (rawRef != null) data.put("raw_ref", rawRef);
    data.put("requested_by", "usr_operador");
    data.put("reason", "core voltou");
    data.put("suppress_external_effects", true);
    String eventId = IngestEnvelopes.eventId("reprocess-test", String.valueOf(++seq));
    return mapper.writeValueAsString(
        IngestEnvelopes.envelope(
            new IngestEnvelopes.Spec(
                eventId,
                "sus.integration.reprocess.requested",
                tenant,
                Map.of("system", "TESTE", "connector", "core-municipal", "source_record_id", "77"),
                data,
                "internal",
                List.of("integration_operations"),
                "corr_reprocess",
                null,
                null,
                false)));
  }

  @Test
  void reprocessaMensagemDaDlqReabrindoAMesmaMensagemEEspelhaNoCore() throws Exception {
    IntegrationMessage dead = deadLettered("77");
    coreUp();

    ReprocessResult result =
        handler.handle(command(dead.id(), "connector-test", "ibge_3143302", null));

    assertThat(result.status()).isEqualTo(ReprocessResult.Status.REPROCESSED);
    assertThat(result.messageId()).isEqualTo(dead.id());
    assertThat(((InMemoryIntegrationMessageLedger) ledger).all()).hasSize(1); // mesma mensagem
    IntegrationMessage m = ledger.findById(dead.id()).orElseThrow();
    assertThat(m.status()).isEqualTo(IntegrationMessageStatus.PUBLISHED);
    assertThat(m.rawRef()).isEqualTo(dead.rawRef());
    core.verify(
        postRequestedFor(urlEqualTo("/api/v1/integration/messages"))
            .withRequestBody(matchingJsonPath("$.id", equalTo(dead.id())))
            .withRequestBody(matchingJsonPath("$.status", equalTo("published"))));
    core.verify(
        postRequestedFor(urlEqualTo("/api/v1/citizens"))
            .withRequestBody(matchingJsonPath("$.source.source_record_id", equalTo("77"))));
    assertThat(
            metrics.counterValue(
                ReprocessCommandHandler.METRIC,
                "connector_id",
                "connector-test",
                "result",
                "reprocessed"))
        .isGreaterThanOrEqualTo(1.0);
  }

  @Test
  void comandoRepetidoMensagemPublicadaEOutroConectorSaoIdempotentes() throws Exception {
    IntegrationMessage dead = deadLettered("78");
    coreUp();
    String cmd = command(dead.id(), "connector-test", "ibge_3143302", null);

    assertThat(handler.handle(cmd).status()).isEqualTo(ReprocessResult.Status.REPROCESSED);
    assertThat(handler.handle(cmd).status()).isEqualTo(ReprocessResult.Status.DUPLICATE);
    // novo comando para a mesma mensagem já publicada: nada é reenviado ao core
    int citizenPosts = core.findAll(postRequestedFor(urlEqualTo("/api/v1/citizens"))).size();
    assertThat(handler.handle(command(dead.id(), "connector-test", "ibge_3143302", null)).status())
        .isEqualTo(ReprocessResult.Status.ALREADY_DONE);
    assertThat(core.findAll(postRequestedFor(urlEqualTo("/api/v1/citizens"))))
        .hasSize(citizenPosts);
    assertThat(handler.handle(command(dead.id(), "connector-outro", "ibge_3143302", null)).status())
        .isEqualTo(ReprocessResult.Status.IGNORED);
    assertThat(handler.handle(command(dead.id(), "connector-test", "ibge_9999999", null)).status())
        .isEqualTo(ReprocessResult.Status.IGNORED);
    assertThat(handler.handle("{nao-json").status()).isEqualTo(ReprocessResult.Status.INVALID);
  }

  @Test
  void ledgerSemAMensagemReconstroiPelaRawZone() throws Exception {
    IntegrationMessage dead = deadLettered("79");
    ((InMemoryIntegrationMessageLedger) ledger).clear(); // ledger em memória após reinício
    coreUp();

    ReprocessResult result =
        handler.handle(command(dead.id(), "connector-test", "ibge_3143302", dead.rawRef()));

    assertThat(result.status()).isEqualTo(ReprocessResult.Status.REPROCESSED);
    IntegrationMessage m = ledger.findById(dead.id()).orElseThrow();
    assertThat(m.status()).isEqualTo(IntegrationMessageStatus.PUBLISHED);
    assertThat(m.sourceRecordId()).isEqualTo("79");
    assertThat(m.entityType()).isEqualTo("citizen");
    assertThat(m.rawSha256()).isEqualTo(dead.rawSha256());

    assertThat(
            handler
                .handle(
                    command(
                        "msg_01HZZZZZZZZZZZZZZZZZZZZZZZ", "connector-test", "ibge_3143302", null))
                .status())
        .isEqualTo(ReprocessResult.Status.NOT_FOUND);
  }

  @Test
  void eventIdDeterministico() {
    String a = IngestEnvelopes.eventId("connector-sia", "abc", "1");
    assertThat(a).matches("^evt_[0-9A-HJKMNP-TV-Z]{26}$");
    assertThat(IngestEnvelopes.eventId("connector-sia", "abc", "1")).isEqualTo(a);
    assertThat(IngestEnvelopes.eventId("connector-sia", "abc", "2")).isNotEqualTo(a);
  }
}
