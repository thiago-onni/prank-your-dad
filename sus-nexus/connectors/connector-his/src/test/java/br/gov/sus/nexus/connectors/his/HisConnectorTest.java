package br.gov.sus.nexus.connectors.his;

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
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.core.IdempotencyKeys;
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
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(WireMockCoreResource.class)
class HisConnectorTest {

  private static final String EPISODES = "/api/v1/hospital/episodes";
  private static final String DISCHARGE_BY_SOURCE =
      "/api/v1/hospital/episodes/by-source/HIS/VIS-1001/discharge";

  @Inject HisConnector connector;
  @Inject ProducerTemplate producer;
  @Inject IntegrationMessageLedger ledger;
  @Inject ConnectorMetrics metrics;
  @Inject HisConfig config;

  @BeforeEach
  void reset() {
    WireMockCoreResource.server.resetAll();
    ((InMemoryIntegrationMessageLedger) ledger).clear();
    WireMockCoreResource.server.stubFor(
        post(urlEqualTo(EPISODES))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"hep_01HZZZZZZZZZZZZZZZZZZZZZZZ\",\"status\":\"admitted\"}")));
    WireMockCoreResource.server.stubFor(
        post(urlPathMatching("/api/v1/hospital/episodes/by-source/.*/discharge"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":\"hep_01HZZZZZZZZZZZZZZZZZZZZZZZ\",\"status\":\"discharged\"}")));
  }

  @Test
  void descriptorCompletoEPerfilDeBorda() {
    assertThat(connector.descriptor().missingFields()).isEmpty();
    assertThat(connector.descriptor().supportedEntities())
        .containsExactly("hospital_movement", "hospital_discharge");
    assertThat(connector.descriptor().supportedProtocols()).contains("mllp", "file-hl7");
    assertThat(config.mllp().enabled()).isFalse();
    assertThat(config.vendor()).isEqualTo("generic");
    assertThat(connector.healthCheck().details()).containsEntry("edge", "true");
  }

  @Test
  @SuppressWarnings("unchecked")
  void a01ViraAdmissaoComDiagnosticoSensivelComoCodigoEAihDoZai() throws IOException {
    CanonicalBatch batch =
        connector.transform(raw("hl7/adt_a01_admit.hl7", CanonicalBatch.HOSPITAL_MOVEMENT));
    assertThat(batch.records()).hasSize(1);
    assertThat(batch.records().get(0).sourceRecordId()).isEqualTo("VIS-1001");
    assertThat(batch.records().get(0).sourceRecordVersion()).isEqualTo("ADT0001");
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat(p)
        .containsEntry("hospital_cnes", "2131234")
        .containsEntry("episode_class", "inpatient")
        .containsEntry("movement", "admit")
        .containsEntry("occurred_at", "2026-01-20T08:25:00-03:00")
        .containsEntry("ward", "CLINICA MEDICA")
        .containsEntry("bed", "B")
        .containsEntry("attending_professional_id", "5551234")
        .containsEntry("admission_source", "emergency")
        .containsEntry("regulation_source_record_id", "REG-77001")
        // F20.0 (sensível) passa como código normalizado; a classificação é do core
        .containsEntry("principal_diagnosis_cid", "F200")
        .containsEntry("aih_number", "AIH-3120001234567")
        .doesNotContainKey("reason");
    assertThat((Map<String, Object>) p.get("source"))
        .containsEntry("system", "HIS")
        .containsEntry("connector", "connector-his")
        .containsEntry("source_record_id", "VIS-1001")
        .containsEntry("source_record_version", "ADT0001")
        .containsEntry("cnes", "2131234");
    assertThat((Map<String, Object>) p.get("citizen_ref"))
        .containsEntry("identifier_system", "CNS")
        .containsEntry("identifier_value", "898001234567891");
    // nome/nascimento do PID nunca vão ao payload
    assertThat(p.toString()).doesNotContain("FICTICIA").doesNotContain("1980-03-05");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  void a02TransferenciaEMudancaDeLeito() throws IOException {
    Map<String, Object> transfer =
        connector
            .transform(raw("hl7/adt_a02_transfer.hl7", CanonicalBatch.HOSPITAL_MOVEMENT))
            .records()
            .get(0)
            .payload();
    assertThat(transfer)
        .containsEntry("movement", "transfer")
        .containsEntry("ward", "UTI ADULTO")
        .containsEntry("bed", "A")
        .containsEntry("attending_professional_id", "5559876")
        .containsEntry("occurred_at", "2026-01-21T09:55:00-03:00");
    Map<String, Object> bed =
        connector
            .transform(raw("hl7/adt_a02_bed_change.hl7", CanonicalBatch.HOSPITAL_MOVEMENT))
            .records()
            .get(0)
            .payload();
    assertThat(bed)
        .containsEntry("movement", "bed_change")
        .containsEntry("ward", "CLINICA MEDICA")
        .containsEntry("bed", "A");
  }

  @Test
  @SuppressWarnings("unchecked")
  void a03ViraAltaBySourceSemTextoDoSumario() throws IOException {
    byte[] content = resource("hl7/adt_a03_discharge.hl7");
    CanonicalBatch batch =
        connector.transform(raw("hl7/adt_a03_discharge.hl7", CanonicalBatch.HOSPITAL_DISCHARGE));
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat((Map<String, Object>) p.get("target_ref"))
        .containsEntry("system", "HIS")
        .containsEntry("source_record_id", "VIS-1001");
    assertThat(p)
        .containsEntry("discharged_at", "2026-01-25T14:30:00-03:00")
        .containsEntry("disposition", "home")
        // DG1 tipo F (alta) prevalece sobre o A (admissão); B24 passa como código
        .containsEntry("principal_diagnosis_cid", "B24")
        .containsEntry("procedures_count", 2)
        .containsEntry("summary_document_sha256", Hashes.sha256Hex(content))
        .containsEntry(
            "summary_document_ref", "raw://connector-his/sha256/" + Hashes.sha256Hex(content));
    assertThat(p.toString()).doesNotContain("alta melhorada").doesNotContain("898001234567891");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  void dispositionLookupCobreObitoEDemaisCodigos() throws IOException {
    assertThat(disposition(resourceText("hl7/adt_a03_death.hl7"))).isEqualTo("deceased");
    String base = resourceText("hl7/adt_a03_discharge.hl7");
    assertThat(disposition(base.replace("|01|", "|06|"))).isEqualTo("home_with_care");
    assertThat(disposition(base.replace("|01|", "|02|"))).isEqualTo("transfer");
    assertThat(disposition(base.replace("|01|", "|04|"))).isEqualTo("transfer");
    assertThat(disposition(base.replace("|01|", "|07|"))).isEqualTo("against_advice");
    assertThat(disposition(base.replace("|01|", "|41|"))).isEqualTo("deceased");
    assertThat(disposition(base.replace("|01|", "|99|"))).isEqualTo("other");
    assertThat(disposition(base.replace("|01|", "|1|"))).isEqualTo("home");
  }

  @Test
  @SuppressWarnings("unchecked")
  void a04RegistroDeUrgenciaComCpfSemTipoInferido() throws IOException {
    CanonicalBatch batch =
        connector.transform(raw("hl7/adt_a04_emergency.hl7", CanonicalBatch.HOSPITAL_MOVEMENT));
    Map<String, Object> p = batch.records().get(0).payload();
    assertThat(p)
        .containsEntry("movement", "admit")
        .containsEntry("episode_class", "emergency")
        .containsEntry("ward", "PRONTO SOCORRO")
        .containsEntry("bed", "BOX3")
        .containsEntry("principal_diagnosis_cid", "R074")
        .doesNotContainKey("aih_number");
    assertThat((Map<String, Object>) p.get("citizen_ref"))
        .containsEntry("identifier_system", "CPF")
        .containsEntry("identifier_value", "12345678909");
    assertThat(connector.validate(batch).isValid()).isTrue();
  }

  @Test
  void a08A11A13ReaplicamCancelamEReadmitem() throws IOException {
    Map<String, Object> update =
        connector
            .transform(raw("hl7/adt_a08_update.hl7", CanonicalBatch.HOSPITAL_MOVEMENT))
            .records()
            .get(0)
            .payload();
    assertThat(update)
        .containsEntry("movement", "admit")
        .containsEntry("principal_diagnosis_cid", "I10")
        .containsEntry("regulation_source_record_id", "REG-77001");
    assertThat(update.get("reason").toString()).contains("A08");

    Map<String, Object> cancel =
        connector
            .transform(raw("hl7/adt_a11_cancel.hl7", CanonicalBatch.HOSPITAL_MOVEMENT))
            .records()
            .get(0)
            .payload();
    assertThat(cancel)
        .containsEntry("movement", "cancel")
        .containsEntry("episode_class", "inpatient");
    assertThat(cancel.get("reason").toString()).contains("A11");

    Map<String, Object> readmit =
        connector
            .transform(raw("hl7/adt_a13_cancel_discharge.hl7", CanonicalBatch.HOSPITAL_MOVEMENT))
            .records()
            .get(0)
            .payload();
    assertThat(readmit)
        .containsEntry("movement", "admit")
        .containsEntry("occurred_at", "2026-01-25T16:00:00-03:00");
    assertThat(readmit.get("reason").toString()).contains("A13");
  }

  @Test
  void a06A07MudamClasseDoEpisodio() throws IOException {
    String a06 =
        resourceText("hl7/adt_a08_update.hl7")
            .replace("ADT^A08^ADT_A01", "ADT^A06^ADT_A06")
            .replace("EVN|A08", "EVN|A06");
    assertThat(
            connector
                .transform(rawText(a06, CanonicalBatch.HOSPITAL_MOVEMENT))
                .records()
                .get(0)
                .payload())
        .containsEntry("movement", "admit")
        .containsEntry("episode_class", "inpatient");
    String a07 =
        resourceText("hl7/adt_a08_update.hl7")
            .replace("ADT^A08^ADT_A01", "ADT^A07^ADT_A06")
            .replace("EVN|A08", "EVN|A07")
            .replace("PV1|1|I|", "PV1|1|O|");
    assertThat(
            connector
                .transform(rawText(a07, CanonicalBatch.HOSPITAL_MOVEMENT))
                .records()
                .get(0)
                .payload())
        .containsEntry("movement", "admit")
        .containsEntry("episode_class", "observation");
  }

  @Test
  void validacaoRejeitaSemCidadaoEAvisaCidForaDoPadrao() throws IOException {
    String semPid =
        resourceText("hl7/adt_a01_admit.hl7")
            .replace(
                "PID|1||898001234567891^^^MS^CNS~12345678909^^^RF^CPF~PRT-4455^^^TASY^PI||",
                "PID|1||||");
    CanonicalBatch batch = connector.transform(rawText(semPid, CanonicalBatch.HOSPITAL_MOVEMENT));
    ValidationReport report = connector.validate(batch);
    assertThat(report.isValid()).isFalse();
    assertThat(report.errors()).anyMatch(i -> i.field().equals("citizen_ref"));

    String cidEstranho = resourceText("hl7/adt_a01_admit.hl7").replace("F20.0^", "XYZ-9^");
    ValidationReport warn =
        connector.validate(
            connector.transform(rawText(cidEstranho, CanonicalBatch.HOSPITAL_MOVEMENT)));
    assertThat(warn.isValid()).isTrue();
    assertThat(warn.issues()).anyMatch(i -> i.field().equals("principal_diagnosis_cid"));
  }

  @Test
  void ackAaAeArEMetricaPorGatilho() throws IOException {
    double before = metrics.counterValue(HisConnector.METRIC_ADT, "trigger", "A01");
    Exchange ok = send(resourceText("hl7/adt_a01_admit.hl7"));
    assertThat(ok.getMessage().getHeader(Hl7Receiver.HEADER_ACK_TYPE)).isEqualTo("AA");
    String ack = ok.getMessage().getBody(String.class);
    assertThat(ack).startsWith("MSH|^~\\&|SUSNEXUS|3143302|TASY_FICT|2131234|");
    assertThat(Hl7Acks.msaCode(ack)).isEqualTo("AA");
    assertThat(ack).contains("MSA|AA|ADT0001");
    assertThat(metrics.counterValue(HisConnector.METRIC_ADT, "trigger", "A01"))
        .isEqualTo(before + 1);

    Exchange bad = send("ISSO NAO E HL7\rPV1|1");
    assertThat(bad.getMessage().getHeader(Hl7Receiver.HEADER_ACK_TYPE)).isEqualTo("AE");
    assertThat(Hl7Acks.msaCode(bad.getMessage().getBody(String.class))).isEqualTo("AE");

    Exchange unsupported = send(resourceText("hl7/adt_a05_unsupported.hl7"));
    assertThat(unsupported.getMessage().getHeader(Hl7Receiver.HEADER_ACK_TYPE)).isEqualTo("AR");
    assertThat(unsupported.getMessage().getBody(String.class)).contains("ADT0005");
  }

  @Test
  void pontaAPontaA01EA03PublicamNoCore() throws IOException {
    send(resourceText("hl7/adt_a01_admit.hl7"));
    send(resourceText("hl7/adt_a03_discharge.hl7"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(
                      ledger.count(
                          CanonicalBatch.HOSPITAL_MOVEMENT,
                          IntegrationMessageStatus.PUBLISHED,
                          null))
                  .isEqualTo(1);
              assertThat(
                      ledger.count(
                          CanonicalBatch.HOSPITAL_DISCHARGE,
                          IntegrationMessageStatus.PUBLISHED,
                          null))
                  .isEqualTo(1);
            });

    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo(EPISODES))
            .withHeader("X-Tenant-Id", equalTo("ibge_3143302"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withHeader("Idempotency-Key", equalTo(IdempotencyKeys.of("VIS-1001", "ADT0001")))
            .withRequestBody(matchingJsonPath("$.source.system", equalTo("HIS")))
            .withRequestBody(matchingJsonPath("$.source.source_record_id", equalTo("VIS-1001")))
            .withRequestBody(matchingJsonPath("$.hospital_cnes", equalTo("2131234")))
            .withRequestBody(matchingJsonPath("$.episode_class", equalTo("inpatient")))
            .withRequestBody(matchingJsonPath("$.movement", equalTo("admit")))
            .withRequestBody(matchingJsonPath("$.admission_source", equalTo("emergency")))
            .withRequestBody(matchingJsonPath("$.principal_diagnosis_cid", equalTo("F200")))
            .withRequestBody(matchingJsonPath("$.aih_number", equalTo("AIH-3120001234567")))
            .withRequestBody(matchingJsonPath("$.citizen_ref.identifier_system", equalTo("CNS")))
            .withRequestBody(
                matchingJsonPath("$.citizen_ref.identifier_value", equalTo("898001234567891"))));
    WireMockCoreResource.server.verify(
        1,
        postRequestedFor(urlEqualTo(DISCHARGE_BY_SOURCE))
            .withHeader("Idempotency-Key", equalTo(IdempotencyKeys.of("VIS-1001", "ADT0003")))
            .withRequestBody(matchingJsonPath("$.disposition", equalTo("home")))
            .withRequestBody(
                matchingJsonPath("$.discharged_at", equalTo("2026-01-25T14:30:00-03:00")))
            .withRequestBody(matchingJsonPath("$.procedures_count", equalTo("2")))
            .withRequestBody(matchingJsonPath("$.principal_diagnosis_cid", equalTo("B24")))
            .withRequestBody(matchingJsonPath("$.summary_document_ref"))
            .withRequestBody(matchingJsonPath("$.summary_document_sha256")));
    String body =
        WireMockCoreResource.server
            .findAll(postRequestedFor(urlEqualTo(DISCHARGE_BY_SOURCE)))
            .get(0)
            .getBodyAsString();
    assertThat(body).doesNotContain("alta melhorada").doesNotContain("target_ref");
  }

  @Test
  void modoArquivoLeHl7ComVariasMensagensAdt() throws IOException {
    Path in = Path.of(config.file().inputDir());
    Files.createDirectories(in);
    String lote =
        resourceText("hl7/adt_a04_emergency.hl7")
            + "\n\n"
            + resourceText("hl7/adt_a03_death.hl7")
            + "\n";
    Path tmp = in.resolve("lote.hl7.part");
    Files.writeString(tmp, lote, StandardCharsets.UTF_8);
    Files.move(tmp, in.resolve("lote.hl7"), StandardCopyOption.ATOMIC_MOVE);

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              WireMockCoreResource.server.verify(
                  1,
                  postRequestedFor(urlEqualTo(EPISODES))
                      .withRequestBody(
                          matchingJsonPath("$.source.source_record_id", equalTo("VIS-2001")))
                      .withRequestBody(matchingJsonPath("$.episode_class", equalTo("emergency"))));
              WireMockCoreResource.server.verify(
                  1,
                  postRequestedFor(
                          urlEqualTo("/api/v1/hospital/episodes/by-source/HIS/VIS-1002/discharge"))
                      .withRequestBody(matchingJsonPath("$.disposition", equalTo("deceased")))
                      .withRequestBody(
                          matchingJsonPath("$.principal_diagnosis_cid", equalTo("I210"))));
            });
  }

  private String disposition(String a03) {
    return connector
        .transform(rawText(a03, CanonicalBatch.HOSPITAL_DISCHARGE))
        .records()
        .get(0)
        .payload()
        .get("disposition")
        .toString();
  }

  private Exchange send(String hl7) {
    return producer.send(HisRoutes.RECEIVE, e -> e.getIn().setBody(hl7));
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
    try (InputStream in = HisConnectorTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      return in.readAllBytes();
    }
  }
}
