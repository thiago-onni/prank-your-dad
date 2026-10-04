package br.gov.sus.nexus.connectors.lis;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.core.IdempotencyKeys;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(WireMockCoreResource.class)
class LisConnectorTest {

  private static final String RESULTS_BY_SOURCE =
      "/api/v1/exams/orders/by-source/LIS/PL-1001/results";

  @Inject LisConnector connector;
  @Inject ProducerTemplate producer;
  @Inject IntegrationMessageLedger ledger;
  @Inject ConnectorMetrics metrics;
  @Inject LisConfig config;

  @BeforeEach
  void reset() {
    WireMockCoreResource.server.resetAll();
    ((InMemoryIntegrationMessageLedger) ledger).clear();
    WireMockCoreResource.server.stubFor(
        post(urlEqualTo("/api/v1/exams/orders"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"exo_01HZZZZZZZZZZZZZZZZZZZZZZZ\",\"status\":\"requested\"}")));
    WireMockCoreResource.server.stubFor(
        post(urlPathMatching("/api/v1/exams/orders/by-source/.*/results"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"exo_01HZZZZZZZZZZZZZZZZZZZZZZZ\",\"status\":\"reported\"}")));
  }

  @Test
  void descriptorCompleto() {
    assertThat(connector.descriptor().missingFields()).isEmpty();
    assertThat(connector.descriptor().supportedEntities())
        .containsExactly("exam_order", "exam_result");
    assertThat(connector.descriptor().supportedProtocols()).contains("mllp");
    assertThat(config.mllp().enabled()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void ormViraPedidoDeExameComCnsDoPid() throws IOException {
    CanonicalBatch batch =
        connector.transform(raw("hl7/orm_o01_hemograma.hl7", CanonicalBatch.EXAM_ORDER));
    assertThat(batch.records()).hasSize(1);
    assertThat(batch.records().get(0).sourceRecordId()).isEqualTo("PL-1001");
    assertThat(batch.records().get(0).sourceRecordVersion()).isEqualTo("MSG0001");
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat(p)
        .containsEntry("status", "requested")
        .containsEntry("requested_at", "2026-01-15T09:30:00-03:00")
        .containsEntry("exam_code", "0202010503")
        .containsEntry("code_system", "SIGTAP")
        .containsEntry("exam_description", "HEMOGRAMA COMPLETO")
        .containsEntry("category", "laboratory")
        .containsEntry("requesting_cnes", "2206997")
        .containsEntry("requesting_professional_id", "1234567")
        .containsEntry("regulation_source_record_id", "GRP-9")
        .containsEntry("priority", "urgent")
        .doesNotContainKey("status_from_control")
        .doesNotContainKey("patient_identifiers");
    assertThat((Map<String, Object>) p.get("source"))
        .containsEntry("system", "LIS")
        .containsEntry("source_record_id", "PL-1001")
        .containsEntry("source_record_version", "MSG0001");
    assertThat((Map<String, Object>) p.get("citizen_ref"))
        .containsEntry("identifier_system", "CNS")
        .containsEntry("identifier_value", "898001234567891");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  void ormDeCancelamentoAtualizaStatusDoPedido() throws IOException {
    String text =
        resourceText("hl7/orm_o01_hemograma.hl7")
            .replace("ORC|NW|", "ORC|CA|")
            .replace("MSG0001", "MSG0009");
    CanonicalBatch batch = connector.transform(rawText(text, CanonicalBatch.EXAM_ORDER));
    assertThat(batch.records().get(0).payload()).containsEntry("status", "cancelled");
    String collected =
        resourceText("hl7/orm_o01_hemograma.hl7")
            .replace("ORC|NW|PL-1001|FL-5001|GRP-9||", "ORC|SC|PL-1001|FL-5001|GRP-9|IP|");
    assertThat(
            connector
                .transform(rawText(collected, CanonicalBatch.EXAM_ORDER))
                .records()
                .get(0)
                .payload())
        .containsEntry("status", "collected");
  }

  @Test
  @SuppressWarnings("unchecked")
  void oruViraResultadoSomenteComObxNumericoECritico() throws IOException {
    byte[] content = resource("hl7/oru_r01_hemograma.hl7");
    double before = metrics.counterValue(LisConnector.METRIC_OBX_SKIPPED, "value_type", "TX");
    CanonicalBatch batch =
        connector.transform(raw("hl7/oru_r01_hemograma.hl7", CanonicalBatch.EXAM_RESULT));
    assertThat(batch.records()).hasSize(1);
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat((Map<String, Object>) p.get("target_ref"))
        .containsEntry("system", "LIS")
        .containsEntry("source_record_id", "PL-1001");
    assertThat(p)
        .containsEntry("status", "final")
        .containsEntry("reported_at", "2026-01-16T10:00:00-03:00")
        .containsEntry("performer_cnes", "2131234")
        .containsEntry("critical", true)
        .containsEntry("document_content_type", LisConnector.HL7_CONTENT_TYPE)
        .containsEntry("document_sha256", Hashes.sha256Hex(content))
        .containsEntry("document_ref", "raw://connector-lis/sha256/" + Hashes.sha256Hex(content));
    List<Map<String, Object>> obs = (List<Map<String, Object>>) p.get("observations");
    assertThat(obs).hasSize(3);
    assertThat(obs.get(0))
        .containsEntry("code", "718-7")
        .containsEntry("code_system", "LOINC")
        .containsEntry("value", new BigDecimal("6.5"))
        .containsEntry("unit", "g/dL")
        .containsEntry("abnormal", true);
    assertThat(obs.get(1))
        .containsEntry("code", "6690-2")
        .containsEntry("value", new BigDecimal("4.0"))
        .containsEntry("abnormal", true);
    assertThat(obs.get(2))
        .containsEntry("code", "4544-3")
        .containsEntry("value", new BigDecimal("38.5"))
        .containsEntry("unit", "%")
        .containsEntry("abnormal", false);
    // nenhum texto livre no payload
    assertThat(p.toString()).doesNotContain("hemolisada").doesNotContain("Laudo descritivo");
    assertThat(metrics.counterValue(LisConnector.METRIC_OBX_SKIPPED, "value_type", "TX"))
        .isEqualTo(before + 1);
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void oruVersao23SemOrcEhToleradoECriticoPorHH() throws IOException {
    CanonicalBatch batch =
        connector.transform(raw("hl7/oru_r01_v23_glicose.hl7", CanonicalBatch.EXAM_RESULT));
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat((Map<String, Object>) p.get("target_ref"))
        .containsEntry("source_record_id", "PL-2002");
    assertThat(p)
        .containsEntry("status", "preliminary")
        .containsEntry("critical", true)
        .containsEntry("reported_at", "2026-01-17T07:55:00-03:00")
        .doesNotContainKey("document_ref");
    assertThat((List<Map<String, Object>>) p.get("observations")).hasSize(1);
    assertThat(((List<Map<String, Object>>) p.get("observations")).get(0))
        .containsEntry("value", new BigDecimal("410"))
        .containsEntry("abnormal", true);
  }

  @Test
  void ackAaAeAr() throws IOException {
    Exchange ok = send(resourceText("hl7/orm_o01_hemograma.hl7"));
    assertThat(ok.getMessage().getHeader(LisRoutes.HEADER_ACK_TYPE)).isEqualTo("AA");
    String ack = ok.getMessage().getBody(String.class);
    assertThat(ack).startsWith("MSH|^~\\&|SUSNEXUS|3143302|LIS_FICT|2131234|");
    assertThat(Hl7Acks.msaCode(ack)).isEqualTo("AA");
    assertThat(ack).contains("MSA|AA|MSG0001");

    Exchange bad = send("ISSO NAO E HL7\rPID|1");
    assertThat(bad.getMessage().getHeader(LisRoutes.HEADER_ACK_TYPE)).isEqualTo("AE");
    assertThat(Hl7Acks.msaCode(bad.getMessage().getBody(String.class))).isEqualTo("AE");

    Exchange unsupported = send(resourceText("hl7/adt_a01_unsupported.hl7"));
    assertThat(unsupported.getMessage().getHeader(LisRoutes.HEADER_ACK_TYPE)).isEqualTo("AR");
    String ar = unsupported.getMessage().getBody(String.class);
    assertThat(Hl7Acks.msaCode(ar)).isEqualTo("AR");
    assertThat(ar).contains("MSG0004");
  }

  @Test
  void pontaAPontaOrmEOruPublicamNoCore() throws IOException {
    send(resourceText("hl7/orm_o01_hemograma.hl7"));
    send(resourceText("hl7/oru_r01_hemograma.hl7"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(
                      ledger.count(
                          CanonicalBatch.EXAM_ORDER, IntegrationMessageStatus.PUBLISHED, null))
                  .isEqualTo(1);
              assertThat(
                      ledger.count(
                          CanonicalBatch.EXAM_RESULT, IntegrationMessageStatus.PUBLISHED, null))
                  .isEqualTo(1);
            });

    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo("/api/v1/exams/orders"))
            .withHeader("X-Tenant-Id", equalTo("ibge_3143302"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withHeader("Idempotency-Key", equalTo(IdempotencyKeys.of("PL-1001", "MSG0001")))
            .withRequestBody(matchingJsonPath("$.source.system", equalTo("LIS")))
            .withRequestBody(matchingJsonPath("$.source.source_record_id", equalTo("PL-1001")))
            .withRequestBody(matchingJsonPath("$.status", equalTo("requested")))
            .withRequestBody(matchingJsonPath("$.exam_code", equalTo("0202010503")))
            .withRequestBody(matchingJsonPath("$.code_system", equalTo("SIGTAP")))
            .withRequestBody(matchingJsonPath("$.category", equalTo("laboratory")))
            .withRequestBody(matchingJsonPath("$.citizen_ref.identifier_system", equalTo("CNS")))
            .withRequestBody(
                matchingJsonPath("$.citizen_ref.identifier_value", equalTo("898001234567891"))));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo(RESULTS_BY_SOURCE))
            .withHeader("Idempotency-Key", equalTo(IdempotencyKeys.of("PL-1001", "MSG0002")))
            .withRequestBody(matchingJsonPath("$.status", equalTo("final")))
            .withRequestBody(matchingJsonPath("$.critical", equalTo("true")))
            .withRequestBody(matchingJsonPath("$.performer_cnes", equalTo("2131234")))
            .withRequestBody(matchingJsonPath("$.observations.length()", equalTo("3")))
            .withRequestBody(
                matchingJsonPath(
                    "$.observations[?(@.code == '718-7' && @.value == 6.5 && @.abnormal == true)]"))
            .withRequestBody(matchingJsonPath("$.document_ref"))
            .withRequestBody(matchingJsonPath("$.document_sha256")));
    String resultBody =
        WireMockCoreResource.server
            .findAll(postRequestedFor(urlEqualTo(RESULTS_BY_SOURCE)))
            .get(0)
            .getBodyAsString();
    assertThat(resultBody)
        .doesNotContain("hemolisada")
        .doesNotContain("Laudo")
        .doesNotContain("target_ref");
  }

  @Test
  void modoArquivoLeHl7ComVariasMensagens() throws IOException {
    Path in = Path.of(config.file().inputDir());
    Files.createDirectories(in);
    String two =
        resourceText("hl7/orm_o01_hemograma.hl7")
                .replace("MSG0001", "MSG0101")
                .replace("PL-1001", "PL-3001")
            + "\n\n"
            + resourceText("hl7/oru_r01_v23_glicose.hl7").replace("MSG0003", "MSG0103");
    Path tmp = in.resolve("lote.hl7.part");
    Files.writeString(tmp, two, StandardCharsets.UTF_8);
    Files.move(tmp, in.resolve("lote.hl7"), StandardCopyOption.ATOMIC_MOVE);

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              WireMockCoreResource.server.verify(
                  1,
                  postRequestedFor(urlEqualTo("/api/v1/exams/orders"))
                      .withRequestBody(
                          matchingJsonPath("$.source.source_record_id", equalTo("PL-3001"))));
              WireMockCoreResource.server.verify(
                  1,
                  postRequestedFor(urlEqualTo("/api/v1/exams/orders/by-source/LIS/PL-2002/results"))
                      .withRequestBody(matchingJsonPath("$.status", equalTo("preliminary"))));
            });
  }

  private Exchange send(String hl7) {
    return producer.send(LisRoutes.RECEIVE, e -> e.getIn().setBody(hl7));
  }

  private static RawMessage raw(String resource, String entityType) throws IOException {
    return rawText(resourceText(resource), entityType);
  }

  private static RawMessage rawText(String text, String entityType) {
    return new RawMessage(
        "test",
        null,
        entityType,
        LisConnector.HL7_CONTENT_TYPE,
        text.getBytes(StandardCharsets.UTF_8),
        Map.of(),
        null);
  }

  private static String resourceText(String name) throws IOException {
    return new String(resource(name), StandardCharsets.UTF_8);
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream in = LisConnectorTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      return in.readAllBytes();
    }
  }
}
