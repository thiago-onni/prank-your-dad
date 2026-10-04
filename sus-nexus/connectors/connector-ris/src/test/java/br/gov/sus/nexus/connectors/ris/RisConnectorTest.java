package br.gov.sus.nexus.connectors.ris;

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
import br.gov.sus.nexus.connectors.sdk.core.dto.ExamResultRegistration;
import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Acks;
import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Receiver;
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
class RisConnectorTest {

  private static final String ORDERS = "/api/v1/exams/orders";
  private static final String RESULTS_5001 = "/api/v1/exams/orders/by-source/RIS/ACC-5001/results";
  private static final String UID_1001 = "1.2.826.0.1.3680043.8.1055.1.20260201.1001";
  private static final String UID_1002 = "1.2.826.0.1.3680043.8.1055.1.20260201.1002";

  @Inject RisConnector connector;
  @Inject ProducerTemplate producer;
  @Inject IntegrationMessageLedger ledger;
  @Inject ConnectorMetrics metrics;
  @Inject RisConfig config;

  @BeforeEach
  void reset() {
    WireMockCoreResource.server.resetAll();
    ((InMemoryIntegrationMessageLedger) ledger).clear();
    WireMockCoreResource.server.stubFor(
        post(urlEqualTo(ORDERS))
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
  void descriptorCompletoECatalogoCarregado() {
    assertThat(connector.descriptor().missingFields()).isEmpty();
    assertThat(connector.descriptor().supportedEntities())
        .containsExactly("exam_order", "exam_result");
    assertThat(connector.descriptor().supportedProtocols())
        .contains("mllp", "file-hl7", "file-dicom-metadata");
    assertThat(config.mllp().enabled()).isFalse();
    assertThat(connector.catalog().size()).isGreaterThan(10);
    assertThat(connector.catalog().sigtap("rx-torax")).contains("0204030153");
    assertThat(connector.healthCheck().details()).containsKey("dicom_dir");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ormViraPedidoDeImagemComSigtapDoCatalogo() throws IOException {
    CanonicalBatch batch =
        connector.transform(raw("hl7/orm_o01_rx_torax.hl7", CanonicalBatch.EXAM_ORDER));
    assertThat(batch.records()).hasSize(1);
    assertThat(batch.records().get(0).sourceRecordId()).isEqualTo("ACC-5001");
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat(p)
        .containsEntry("status", "requested")
        .containsEntry("requested_at", "2026-02-01T09:00:00-03:00")
        .containsEntry("exam_code", "0204030153")
        .containsEntry("code_system", "SIGTAP")
        .containsEntry("exam_description", "RADIOGRAFIA DE TORAX PA E PERFIL")
        .containsEntry("category", "imaging")
        .containsEntry("requesting_cnes", "2206997")
        .containsEntry("requesting_professional_id", "1234567")
        .containsEntry("regulation_source_record_id", "REG-55001")
        .containsEntry("priority", "routine");
    assertThat((Map<String, Object>) p.get("source"))
        .containsEntry("system", "RIS")
        .containsEntry("connector", "connector-ris")
        .containsEntry("source_record_id", "ACC-5001")
        .containsEntry("source_record_version", "RIS0001");
    assertThat((Map<String, Object>) p.get("citizen_ref"))
        .containsEntry("identifier_system", "CNS")
        .containsEntry("identifier_value", "898001234567891");
    assertThat(p.toString()).doesNotContain("FICTICIA").doesNotContain("1980-03-05");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  void codigoDoExameSigtapDiretoLoincOuLocal() throws IOException {
    assertThat(connector.examCode("0206010079", "SIGTAP")).containsExactly("0206010079", "SIGTAP");
    assertThat(connector.examCode("tc-cranio", "L")).containsExactly("0206010079", "SIGTAP");
    assertThat(connector.examCode("24627-2", "LN")).containsExactly("24627-2", "LOINC");
    assertThat(connector.examCode("PET-CT-XYZ", "L")).containsExactly("PET-CT-XYZ", "LOCAL");

    String local =
        resourceText("hl7/orm_o01_rx_torax.hl7")
            .replace("RX-TORAX^RADIOGRAFIA DE TORAX PA E PERFIL^L", "PET-CT-XYZ^PET CT^L");
    Map<String, Object> p =
        connector.transform(rawText(local, CanonicalBatch.EXAM_ORDER)).records().get(0).payload();
    assertThat(p).containsEntry("exam_code", "PET-CT-XYZ").containsEntry("code_system", "LOCAL");
  }

  @Test
  @SuppressWarnings("unchecked")
  void oruViraResultadoSemTextoDoLaudoECriticoPorObx8OuObr13() throws IOException {
    byte[] content = resource("hl7/oru_r01_laudo.hl7");
    double skippedBefore =
        metrics.counterValue(RisConnector.METRIC_REPORT_TEXT_SKIPPED, "value_type", "TX");
    CanonicalBatch batch =
        connector.transform(raw("hl7/oru_r01_laudo.hl7", CanonicalBatch.EXAM_RESULT));
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat((Map<String, Object>) p.get("target_ref"))
        .containsEntry("system", "RIS")
        .containsEntry("source_record_id", "ACC-5001");
    assertThat(p)
        .containsEntry("status", "final")
        .containsEntry("reported_at", "2026-02-01T15:00:00-03:00")
        .containsEntry("performer_cnes", "2131234")
        .containsEntry("critical", false)
        .containsEntry("observations", List.of())
        .containsEntry("document_content_type", Hl7Receiver.HL7_CONTENT_TYPE)
        .containsEntry("document_sha256", Hashes.sha256Hex(content))
        .containsEntry("document_ref", "raw://connector-ris/sha256/" + Hashes.sha256Hex(content));
    assertThat(p.toString())
        .doesNotContain("Opacidade")
        .doesNotContain("Consolidacao")
        .doesNotContain("898001234567891");
    assertThat(metrics.counterValue(RisConnector.METRIC_REPORT_TEXT_SKIPPED, "value_type", "TX"))
        .isEqualTo(skippedBefore + 1);
    assertThat(connector.validate(batch).isValid()).isTrue();

    // OBX-8 = C (ris.critical.flags) marca o laudo como crítico
    Map<String, Object> critico =
        connector
            .transform(raw("hl7/oru_r01_laudo_critico.hl7", CanonicalBatch.EXAM_RESULT))
            .records()
            .get(0)
            .payload();
    assertThat(critico).containsEntry("critical", true);
    assertThat(critico.toString()).doesNotContain("Hemorragia");

    // OBR-13 contendo o marcador (ris.critical.obr-value=CRITICO) também marca
    String obr13 = resourceText("hl7/oru_r01_laudo.hl7").replace("DOR TORACICA", "ACHADO CRITICO");
    assertThat(
            connector
                .transform(rawText(obr13, CanonicalBatch.EXAM_RESULT))
                .records()
                .get(0)
                .payload())
        .containsEntry("critical", true);
  }

  @Test
  @SuppressWarnings("unchecked")
  void metadadosDicomJsonViramReferenciaAoEstudoSemCopiarImagem() throws IOException {
    byte[] content = resource("dicom/studies.json");
    RawMessage raw =
        new RawMessage(
            "studies.json",
            null,
            CanonicalBatch.EXAM_RESULT,
            RisConnector.DICOM_JSON,
            content,
            Map.of(RisConnector.META_SOURCE, RisConnector.META_SOURCE_DICOM),
            null);
    CanonicalBatch batch = connector.transform(raw);
    // o terceiro estudo não tem StudyInstanceUID e é ignorado
    assertThat(batch.records()).hasSize(2);
    assertThat(batch.records().get(0).sourceRecordId()).isEqualTo("ACC-5001");
    assertThat(batch.records().get(0).sourceRecordVersion()).isEqualTo(UID_1001);
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat((Map<String, Object>) p.get("target_ref"))
        .containsEntry("system", "RIS")
        .containsEntry("source_record_id", "ACC-5001");
    assertThat(p)
        .containsEntry("status", "final")
        .containsEntry("reported_at", "2026-02-01T10:15:00-03:00")
        .containsEntry("performer_cnes", "2131234")
        .containsEntry("critical", false)
        .containsEntry("observations", List.of())
        .containsEntry("document_content_type", ExamResultRegistration.DICOM_STUDY_REF_CONTENT_TYPE)
        .containsEntry("document_ref", "dicom://PACS_FICT/" + UID_1001)
        .containsEntry("document_sha256", Hashes.sha256Hex(UID_1001));
    // tags hexadecimais e AE Title padrão (ris.dicom.ae-title) no segundo estudo
    Map<String, Object> p2 = batch.records().get(1).payload();
    assertThat(p2)
        .containsEntry("document_ref", "dicom://PACS_FICT/" + UID_1002)
        .containsEntry("document_sha256", Hashes.sha256Hex(UID_1002))
        .containsEntry("reported_at", "2026-02-01T11:30:00-03:00");
    assertThat((Map<String, Object>) p2.get("target_ref"))
        .containsEntry("source_record_id", "ACC-5002");
    // nome/ID do paciente, modalidade e contagens ficam só na raw zone
    assertThat(batch.records().toString())
        .doesNotContain("FICTICI")
        .doesNotContain("898001234567891")
        .doesNotContain("\"CR\"");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  void ackAaAeArEMetricaPorTipo() throws IOException {
    double before = metrics.counterValue(RisConnector.METRIC_MESSAGES, "kind", "exam_order");
    Exchange ok = send(resourceText("hl7/orm_o01_rx_torax.hl7"));
    assertThat(ok.getMessage().getHeader(Hl7Receiver.HEADER_ACK_TYPE)).isEqualTo("AA");
    String ack = ok.getMessage().getBody(String.class);
    assertThat(ack).startsWith("MSH|^~\\&|SUSNEXUS|3143302|RIS_FICT|2131234|");
    assertThat(Hl7Acks.msaCode(ack)).isEqualTo("AA");
    assertThat(ack).contains("MSA|AA|RIS0001");
    assertThat(metrics.counterValue(RisConnector.METRIC_MESSAGES, "kind", "exam_order"))
        .isEqualTo(before + 1);

    Exchange bad = send("ISSO NAO E HL7\rOBR|1");
    assertThat(bad.getMessage().getHeader(Hl7Receiver.HEADER_ACK_TYPE)).isEqualTo("AE");
    assertThat(Hl7Acks.msaCode(bad.getMessage().getBody(String.class))).isEqualTo("AE");

    Exchange unsupported = send(resourceText("hl7/adt_a01_unsupported.hl7"));
    assertThat(unsupported.getMessage().getHeader(Hl7Receiver.HEADER_ACK_TYPE)).isEqualTo("AR");
    assertThat(unsupported.getMessage().getBody(String.class)).contains("RIS0009");
  }

  @Test
  void pontaAPontaOrmEOruPublicamNoCore() throws IOException {
    send(resourceText("hl7/orm_o01_rx_torax.hl7"));
    send(resourceText("hl7/oru_r01_laudo.hl7"));

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
        postRequestedFor(urlEqualTo(ORDERS))
            .withHeader("X-Tenant-Id", equalTo("ibge_3143302"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withHeader("Idempotency-Key", equalTo(IdempotencyKeys.of("ACC-5001", "RIS0001")))
            .withRequestBody(matchingJsonPath("$.source.system", equalTo("RIS")))
            .withRequestBody(matchingJsonPath("$.source.source_record_id", equalTo("ACC-5001")))
            .withRequestBody(matchingJsonPath("$.exam_code", equalTo("0204030153")))
            .withRequestBody(matchingJsonPath("$.code_system", equalTo("SIGTAP")))
            .withRequestBody(matchingJsonPath("$.category", equalTo("imaging")))
            .withRequestBody(matchingJsonPath("$.citizen_ref.identifier_system", equalTo("CNS"))));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo(RESULTS_5001))
            .withHeader("Idempotency-Key", equalTo(IdempotencyKeys.of("ACC-5001", "RIS0002")))
            .withRequestBody(matchingJsonPath("$.status", equalTo("final")))
            .withRequestBody(matchingJsonPath("$.critical", equalTo("false")))
            .withRequestBody(
                matchingJsonPath("$.document_content_type", equalTo(Hl7Receiver.HL7_CONTENT_TYPE)))
            .withRequestBody(matchingJsonPath("$.document_sha256")));
    String body =
        WireMockCoreResource.server
            .findAll(postRequestedFor(urlEqualTo(RESULTS_5001)))
            .get(0)
            .getBodyAsString();
    assertThat(body)
        .doesNotContain("Opacidade")
        .doesNotContain("FICTICIA")
        .doesNotContain("target_ref")
        .contains("\"observations\":[]");
  }

  @Test
  void modoArquivoDicomCsvPublicaReferenciasDosEstudos() throws IOException {
    Path in = Path.of(config.dicom().inputDir());
    Files.createDirectories(in);
    Path tmp = in.resolve("export.csv.part");
    Files.write(tmp, resource("dicom/studies.csv"));
    Files.move(tmp, in.resolve("export.csv"), StandardCopyOption.ATOMIC_MOVE);

    String uid6001 = "1.2.826.0.1.3680043.8.1055.1.20260202.2001";
    String uid6002 = "1.2.826.0.1.3680043.8.1055.1.20260202.2002";
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              WireMockCoreResource.server.verify(
                  1,
                  postRequestedFor(
                          urlEqualTo("/api/v1/exams/orders/by-source/RIS/ACC-6001/results"))
                      .withHeader(
                          "Idempotency-Key", equalTo(IdempotencyKeys.of("ACC-6001", uid6001)))
                      .withRequestBody(
                          matchingJsonPath(
                              "$.document_content_type",
                              equalTo(ExamResultRegistration.DICOM_STUDY_REF_CONTENT_TYPE)))
                      .withRequestBody(
                          matchingJsonPath(
                              "$.document_ref", equalTo("dicom://PACS_FICT/" + uid6001)))
                      .withRequestBody(
                          matchingJsonPath("$.document_sha256", equalTo(Hashes.sha256Hex(uid6001))))
                      .withRequestBody(
                          matchingJsonPath("$.reported_at", equalTo("2026-02-02T08:30:00-03:00"))));
              WireMockCoreResource.server.verify(
                  1,
                  postRequestedFor(
                          urlEqualTo("/api/v1/exams/orders/by-source/RIS/ACC-6002/results"))
                      .withRequestBody(
                          matchingJsonPath(
                              "$.document_ref", equalTo("dicom://PACS_FICT/" + uid6002))));
            });
    String body =
        WireMockCoreResource.server
            .findAll(
                postRequestedFor(urlEqualTo("/api/v1/exams/orders/by-source/RIS/ACC-6001/results")))
            .get(0)
            .getBodyAsString();
    assertThat(body).doesNotContain("FICTICIO").doesNotContain("\"US\"");
    assertThat(metrics.counterValue(RisConnector.METRIC_MESSAGES, "kind", "dicom_study"))
        .isGreaterThanOrEqualTo(1);
  }

  private Exchange send(String hl7) {
    return producer.send(RisRoutes.RECEIVE, e -> e.getIn().setBody(hl7));
  }

  private static RawMessage raw(String resource, String entityType) throws IOException {
    return rawText(resourceText(resource), entityType);
  }

  private static RawMessage rawText(String text, String entityType) {
    return new RawMessage(
        "test",
        null,
        entityType,
        Hl7Receiver.HL7_CONTENT_TYPE,
        text.getBytes(StandardCharsets.UTF_8),
        Map.of(),
        null);
  }

  private static String resourceText(String name) throws IOException {
    return new String(resource(name), StandardCharsets.UTF_8);
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream in = RisConnectorTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      return in.readAllBytes();
    }
  }
}
