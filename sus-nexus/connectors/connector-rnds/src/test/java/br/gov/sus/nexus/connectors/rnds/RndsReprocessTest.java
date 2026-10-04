package br.gov.sus.nexus.connectors.rnds;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.rnds.RndsDispatcher.Outcome;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmission;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStatus;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStore;
import br.gov.sus.nexus.connectors.sdk.events.IngestEnvelopes;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.reprocess.ReprocessCommandHandler;
import br.gov.sus.nexus.connectors.sdk.reprocess.ReprocessResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comando {@code sus.integration.reprocess.requested} (tópico {@code sus.integration.command.v1}) →
 * reenvio de submissões {@code failed} pelo {@link RndsReprocessor} (handler genérico do SDK).
 */
@QuarkusTest
@QuarkusTestResource(WireMockRndsResource.class)
class RndsReprocessTest {

  private static final String EHR = "/rnds-ehr/api/fhir/r4/Bundle";
  private static final String MESSAGES = "/api/v1/integration/messages";
  private static int seq = 5000;
  private static int cmdSeq;

  @Inject RndsDispatcher dispatcher;
  @Inject RndsSubmissionStore submissions;
  @Inject IntegrationMessageLedger ledger;
  @Inject RndsAuthClient auth;
  @Inject FhirGatewayClient fhirClient;
  @Inject ReprocessCommandHandler commands;
  @Inject ObjectMapper mapper;
  @Inject @Any InMemoryConnector bus;

  private static WireMockServer wm() {
    return WireMockRndsResource.server;
  }

  @BeforeEach
  void setUp() {
    wm().resetAll();
    submissions.clear();
    ((InMemoryIntegrationMessageLedger) ledger).clear();
    auth.invalidate();
    fhirClient.invalidate();
    commands.reset();
    wm().stubFor(
            post(urlEqualTo("/kc/token"))
                .willReturn(json(200, "{\"access_token\":\"kc-token\",\"expires_in\":300}")));
    fhir("DiagnosticReport/" + Fixtures.DR_ID, "/fhir/diagnostic-report.json");
    fhir("Observation/" + Fixtures.DR_ID + "-obs-1", "/fhir/observation-1.json");
    fhir("Observation/" + Fixtures.DR_ID + "-obs-2", "/fhir/observation-2.json");
    fhir("ServiceRequest/01JE28JT97KB6CQ643DZVMXXQK", "/fhir/service-request.json");
    fhir("Patient/" + Fixtures.PATIENT_ID, "/fhir/patient.json");
    wm().stubFor(post(urlPathMatching("/api/v1/integration/.*")).willReturn(json(200, "{}")));
    wm().stubFor(
            get(urlEqualTo("/rnds-auth/api/token"))
                .willReturn(json(200, "{\"access_token\":\"rnds-tok\",\"expires_in\":1800}")));
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      int status, String body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  private static void fhir(String path, String fixture) {
    wm().stubFor(
            get(urlEqualTo("/fhir/r4/" + path))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/fhir+json")
                        .withBody(Fixtures.read(fixture))));
  }

  private static void ehr(int status) {
    wm().stubFor(
            post(urlEqualTo(EHR))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Location", "https://ehr.exemplo/Bundle/prot-reprocesso")));
  }

  private static int ehrPosts() {
    return wm().findAll(postRequestedFor(urlEqualTo(EHR))).size();
  }

  private String command(String messageId, String sourceRecordId) throws Exception {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", "requested");
    data.put("message_id", messageId);
    data.put("connector_id", "connector-rnds");
    data.put("source_system", "RNDS");
    data.put("source_record_id", sourceRecordId);
    data.put("requested_by", "usr_operador_integracao");
    data.put("reason", "RNDS indisponível na janela anterior");
    data.put("suppress_external_effects", true);
    return mapper.writeValueAsString(
        IngestEnvelopes.envelope(
            new IngestEnvelopes.Spec(
                IngestEnvelopes.eventId("rnds-reprocess-test", String.valueOf(++cmdSeq)),
                "sus.integration.reprocess.requested",
                "ibge_3143302",
                Map.of(
                    "system",
                    "RNDS",
                    "connector",
                    "core-municipal",
                    "source_record_id",
                    sourceRecordId),
                data,
                "internal",
                List.of("integration_operations"),
                "corr_reprocess_rnds",
                null,
                null,
                false)));
  }

  private RndsSubmission failedSubmission(String eventId) {
    ehr(503);
    Outcome outcome =
        dispatcher.handle(
            "resultado-exame", Fixtures.examResultEvent(eventId, "available", "final"));
    assertThat(outcome).isEqualTo(Outcome.DEAD_LETTERED);
    RndsSubmission s = submissions.findByEventId(eventId).orElseThrow();
    assertThat(s.status()).isEqualTo(RndsSubmissionStatus.FAILED);
    return s;
  }

  @Test
  void comandoKafkaReenviaSubmissaoFailedReabrindoAMesmaMensagem() throws Exception {
    String eventId = Fixtures.eventId(++seq);
    RndsSubmission failed = failedSubmission(eventId);
    String messageId = failed.integrationMessageId();
    int postsBefore = ehrPosts();
    ehr(201);

    bus.source("rnds-integration-command").send(command(messageId, eventId));

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                submissions
                    .findByEventId(eventId)
                    .map(s -> s.status() == RndsSubmissionStatus.ACCEPTED)
                    .orElse(false));
    RndsSubmission s = submissions.findByEventId(eventId).orElseThrow();
    assertThat(s.protocol()).isEqualTo("https://ehr.exemplo/Bundle/prot-reprocesso");
    assertThat(s.integrationMessageId()).isEqualTo(messageId);
    assertThat(ehrPosts()).isEqualTo(postsBefore + 1);
    assertThat(((InMemoryIntegrationMessageLedger) ledger).all()).hasSize(1);
    assertThat(ledger.findById(messageId).orElseThrow().status())
        .isEqualTo(IntegrationMessageStatus.PUBLISHED);
    wm().verify(
            postRequestedFor(urlEqualTo(MESSAGES))
                .withRequestBody(matchingJsonPath("$.id", equalTo(messageId)))
                .withRequestBody(matchingJsonPath("$.status", equalTo("published"))));
  }

  @Test
  void submissaoAceitaOuComandoDeOutroConectorNaoReenvia() throws Exception {
    ehr(201);
    String eventId = Fixtures.eventId(++seq);
    assertThat(
            dispatcher.handle(
                "resultado-exame", Fixtures.examResultEvent(eventId, "available", "final")))
        .isEqualTo(Outcome.ACCEPTED);
    String messageId = submissions.findByEventId(eventId).orElseThrow().integrationMessageId();
    int posts = ehrPosts();

    assertThat(commands.handle(command(messageId, eventId)).status())
        .isEqualTo(ReprocessResult.Status.ALREADY_DONE);
    String other = command(messageId, eventId).replace("\"connector-rnds\"", "\"connector-sia\"");
    assertThat(commands.handle(other).status()).isEqualTo(ReprocessResult.Status.IGNORED);
    assertThat(ehrPosts()).isEqualTo(posts);
  }

  @Test
  void reprocessamentoQueFalhaDeNovoVoltaParaFailed() throws Exception {
    String eventId = Fixtures.eventId(++seq);
    RndsSubmission failed = failedSubmission(eventId);

    ReprocessResult result =
        commands.handle(command(failed.integrationMessageId(), eventId)); // EHR ainda 503

    assertThat(result.status()).isEqualTo(ReprocessResult.Status.DEAD_LETTERED);
    assertThat(submissions.findByEventId(eventId).orElseThrow().status())
        .isEqualTo(RndsSubmissionStatus.FAILED);
    assertThat(ledger.findById(failed.integrationMessageId()).orElseThrow().status())
        .isEqualTo(IntegrationMessageStatus.DEAD_LETTERED);
  }
}
