package br.gov.sus.nexus.connectors.sdk.runtime;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.core.IdempotencyKeys;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import br.gov.sus.nexus.connectors.sdk.raw.RawMessageStore;
import br.gov.sus.nexus.connectors.sdk.retry.DeadLetter;
import br.gov.sus.nexus.connectors.sdk.retry.DeadLetterSink;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@QuarkusTestResource(WireMockCoreResource.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PipelineEndToEndTest {

  @Inject ProducerTemplate producer;
  @Inject IntegrationMessageLedger ledger;
  @Inject DeadLetterSink dlq;
  @Inject RawMessageStore rawStore;
  @Inject ConnectorMetrics metrics;
  @Inject TestConnector connector;

  private WireMockServer core;

  @BeforeEach
  void reset() {
    core = WireMockCoreResource.server;
    core.resetAll();
    ((InMemoryIntegrationMessageLedger) ledger).clear();
  }

  private static RawMessage raw(String id, String line) {
    return new RawMessage(
        id, "v1", "citizen", "text/csv", line.getBytes(), Map.of("origem", "teste"), Instant.now());
  }

  @Test
  @Order(1)
  void publicaCidadaoComHeadersDeIdempotenciaETenant() {
    core.stubFor(
        post(urlEqualTo("/api/v1/citizens"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"municipal_citizen_id\":\"cit_01HZZZZZZZZZZZZZZZZZZZZZZZ\",\"classification\":\"new\",\"method\":\"deterministic\"}")));

    producer.sendBodyAndHeader(
        ConnectorRuntime.INGEST,
        raw("42", "42;José da Silva;05031980;M;123.456.789-09"),
        PipelineHeaders.CORRELATION_ID,
        "corr_TESTE1");

    String expectedKey = IdempotencyKeys.of("42", "v1");
    assertThat(expectedKey).isEqualTo(Hashes.sha256Hex("42:v1"));
    core.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/citizens"))
            .withHeader("Idempotency-Key", equalTo(expectedKey))
            .withHeader("X-Tenant-Id", equalTo("ibge_3143302"))
            .withHeader("X-Correlation-Id", equalTo("corr_TESTE1"))
            .withHeader("X-Purpose-Of-Use", equalTo("integration_operations"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withRequestBody(
                matchingJsonPath("$.demographics.legal_name", equalTo("JOSE DA SILVA")))
            .withRequestBody(matchingJsonPath("$.demographics.birthdate", equalTo("1980-03-05")))
            .withRequestBody(matchingJsonPath("$.identifiers[0].value", equalTo("12345678909")))
            .withRequestBody(matchingJsonPath("$.source.source_record_id", equalTo("42"))));

    List<IntegrationMessage> all = ((InMemoryIntegrationMessageLedger) ledger).all();
    assertThat(all).hasSize(1);
    IntegrationMessage m = all.get(0);
    assertThat(m.status()).isEqualTo(IntegrationMessageStatus.PUBLISHED);
    assertThat(m.id()).startsWith("msg_");
    assertThat(m.correlationId()).isEqualTo("corr_TESTE1");
    assertThat(m.rawSha256())
        .isEqualTo(Hashes.sha256Hex("42;José da Silva;05031980;M;123.456.789-09".getBytes()));
    assertThat(m.attempts()).isZero();
    assertThat(
            rawStore.read(
                new br.gov.sus.nexus.connectors.sdk.raw.RawMessageRef(
                    m.rawRef(), m.rawSha256(), 0, null)))
        .isPresent();
    assertThat(
            metrics.counterValue(
                ConnectorMetrics.PROCESSED,
                "connector_id",
                "connector-test",
                "entity_type",
                "citizen"))
        .isGreaterThanOrEqualTo(1.0);
  }

  @Test
  @Order(2)
  void loteInvalidoVaiParaDlqSemRetry() {
    producer.sendBody(ConnectorRuntime.INGEST, raw("43", "43; ;05031980;M;"));

    core.verify(0, postRequestedFor(urlEqualTo("/api/v1/citizens")));
    IntegrationMessage m = ((InMemoryIntegrationMessageLedger) ledger).all().get(0);
    assertThat(m.status()).isEqualTo(IntegrationMessageStatus.DEAD_LETTERED);
    assertThat(m.attempts()).isEqualTo(1);
    assertThat(m.lastError().stage()).isEqualTo("transform");
    assertThat(connector.getErrorDetails(m.id()).code()).isEqualTo("ConnectorException");
    assertThat(dlq.open(100)).extracting(DeadLetter::messageId).contains(m.id());
  }

  @Test
  @Order(3)
  void erroPermanenteDoCoreVaiParaDlqSemRetry() {
    core.stubFor(
        post(urlEqualTo("/api/v1/citizens"))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("{\"title\":\"cpf inválido 123.456.789-09\"}")));

    producer.sendBody(ConnectorRuntime.INGEST, raw("44", "44;Ana;01012000;F;"));

    core.verify(1, postRequestedFor(urlEqualTo("/api/v1/citizens")));
    IntegrationMessage m = ((InMemoryIntegrationMessageLedger) ledger).all().get(0);
    assertThat(m.status()).isEqualTo(IntegrationMessageStatus.DEAD_LETTERED);
    assertThat(m.lastError().stage()).isEqualTo("publish");
    assertThat(m.lastError().message())
        .contains("422")
        .contains("***.***.***-09")
        .doesNotContain("123.456.789");
  }

  @Test
  @Order(4)
  void erroTransitorioDoCoreFazRetryEDepoisDlq() {
    core.stubFor(post(urlEqualTo("/api/v1/citizens")).willReturn(aResponse().withStatus(503)));

    producer.sendBody(ConnectorRuntime.INGEST, raw("45", "45;Bia;01012000;F;"));

    // 3 tentativas do pipeline × (1 chamada + 1 retry do CoreClient) = 6 requisições
    core.verify(
        6,
        postRequestedFor(urlEqualTo("/api/v1/citizens"))
            .withHeader("Idempotency-Key", matching("[a-f0-9]{64}")));
    IntegrationMessage m = ((InMemoryIntegrationMessageLedger) ledger).all().get(0);
    assertThat(m.status()).isEqualTo(IntegrationMessageStatus.DEAD_LETTERED);
    assertThat(m.attempts()).isEqualTo(3);
    assertThat(
            metrics.counterValue(
                ConnectorMetrics.FAILED, "connector_id", "connector-test", "stage", "publish"))
        .isGreaterThanOrEqualTo(1.0);
  }

  @Test
  @Order(5)
  void retryTransitorioRecuperaQuandoCoreVolta() {
    core.stubFor(
        post(urlEqualTo("/api/v1/citizens"))
            .inScenario("flaky")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("ok"));
    core.stubFor(
        post(urlEqualTo("/api/v1/citizens"))
            .inScenario("flaky")
            .whenScenarioStateIs("ok")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"municipal_citizen_id\":\"cit_X\",\"classification\":\"confirmed\",\"method\":\"cpf\"}")));

    producer.sendBody(ConnectorRuntime.INGEST, raw("46", "46;Caio;01012000;M;"));

    IntegrationMessage m = ((InMemoryIntegrationMessageLedger) ledger).all().get(0);
    assertThat(m.status()).isEqualTo(IntegrationMessageStatus.PUBLISHED);
    // o retry do CoreClient (SmallRye FT) recupera antes de o pipeline registrar falha
    assertThat(m.attempts()).isZero();
    core.verify(2, postRequestedFor(urlEqualTo("/api/v1/citizens")));
  }
}
