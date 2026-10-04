package br.gov.sus.nexus.connectors.rnds;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.connectors.rnds.RndsDispatcher.Outcome;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmission;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStatus;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStore;
import br.gov.sus.nexus.connectors.sdk.api.Period;
import br.gov.sus.nexus.connectors.sdk.api.ReconciliationReport;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.awaitility.Awaitility;
import org.hl7.fhir.r4.formats.JsonParser;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(WireMockRndsResource.class)
class RndsConnectorTest {

  private static final String EHR = "/rnds-ehr/api/fhir/r4/Bundle";
  private static final String AUTH = "/rnds-auth/api/token";
  private static final String MESSAGES = "/api/v1/integration/messages";
  private static final String LOCATION =
      "https://ehr-services.exemplo/api/fhir/r4/Bundle/9b7c2d1e-protocolo-rnds";

  @Inject RndsDispatcher dispatcher;
  @Inject RndsSubmissionStore submissions;
  @Inject IntegrationMessageLedger ledger;
  @Inject RndsAuthClient auth;
  @Inject RndsMetrics metrics;
  @Inject RndsRoutes routes;
  @Inject RndsConnector connector;
  @Inject FhirGatewayClient fhirClient;

  @Inject @Any InMemoryConnector bus;

  private final List<String> logs = new ArrayList<>();
  private Handler capture;
  private static int seq = 1000;

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
    // Keycloak (client credentials do fhir-gateway)
    wm().stubFor(
            post(urlEqualTo("/kc/token"))
                .willReturn(json(200, "{\"access_token\":\"kc-token\",\"expires_in\":300}")));
    fhir("DiagnosticReport/" + Fixtures.DR_ID, "/fhir/diagnostic-report.json");
    fhir("Observation/" + Fixtures.DR_ID + "-obs-1", "/fhir/observation-1.json");
    fhir("Observation/" + Fixtures.DR_ID + "-obs-2", "/fhir/observation-2.json");
    fhir("ServiceRequest/01JE28JT97KB6CQ643DZVMXXQK", "/fhir/service-request.json");
    fhir("Patient/" + Fixtures.PATIENT_ID, "/fhir/patient.json");
    // Core: ledger espelho, heartbeat e reconciliação
    wm().stubFor(post(urlPathMatching("/api/v1/integration/.*")).willReturn(json(200, "{}")));
    // RNDS: autenticação (HTTPS + certificado de cliente) e EHR
    wm().stubFor(
            get(urlEqualTo(AUTH))
                .willReturn(json(200, "{\"access_token\":\"rnds-tok\",\"expires_in\":1800}")));
    wm().stubFor(
            post(urlEqualTo(EHR))
                .willReturn(aResponse().withStatus(201).withHeader("Location", LOCATION)));

    capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            String msg =
                record instanceof ExtLogRecord ext
                    ? ext.getFormattedMessage()
                    : record.getMessage();
            synchronized (logs) {
              logs.add(msg);
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    Logger.getLogger("br.gov.sus.nexus").addHandler(capture);
  }

  @AfterEach
  void tearDown() {
    Logger.getLogger("br.gov.sus.nexus").removeHandler(capture);
  }

  private static String nextEventId() {
    return Fixtures.eventId(++seq);
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

  private static List<LoggedRequest> ehrPosts() {
    return wm().findAll(postRequestedFor(urlEqualTo(EHR)));
  }

  private static Bundle postedBundle() throws IOException {
    List<LoggedRequest> posts = ehrPosts();
    assertThat(posts).isNotEmpty();
    return (Bundle) new JsonParser().parse(posts.get(posts.size() - 1).getBodyAsString());
  }

  private RndsSubmission submission(String eventId) {
    return submissions.findByEventId(eventId).orElseThrow();
  }

  // ------------------------------------------------------------------------------------------

  @Test
  void montaBundleAPartirDoFhirGatewayEEnvia201ComLocation() throws IOException {
    String eventId = nextEventId();
    double accepted = metrics.count("resultado-exame", "accepted");

    Outcome outcome =
        dispatcher.handle(
            "resultado-exame", Fixtures.examResultEvent(eventId, "available", "final"));

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    // fhir-gateway lido com client credentials (escopo system/*.read)
    wm().verify(
            postRequestedFor(urlEqualTo("/kc/token"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("scope=system%2F*.read")));
    wm().verify(
            getRequestedFor(urlEqualTo("/fhir/r4/DiagnosticReport/" + Fixtures.DR_ID))
                .withHeader("Authorization", equalTo("Bearer kc-token"))
                .withHeader("X-Tenant-Id", equalTo("ibge_3143302")));
    // envio à RNDS: token da autenticação mTLS + CPF do solicitante
    assertThat(ehrPosts()).hasSize(1);
    LoggedRequest post = ehrPosts().get(0);
    assertThat(post.getHeader("X-Authorization-Server")).isEqualTo("Bearer rnds-tok");
    assertThat(post.getHeader("Authorization")).isEqualTo("52998224725");
    assertThat(post.getHeader("Content-Type")).startsWith("application/fhir+json");

    Bundle bundle = postedBundle();
    assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.DOCUMENT);
    assertThat(bundle.getIdentifier().getSystem())
        .isEqualTo("http://www.saude.gov.br/fhir/r4/NamingSystem/BRRNDS-2222222");
    assertThat(bundle.getIdentifier().getValue()).isEqualTo(Fixtures.EXR + "-v2");
    assertThat(bundle.getEntry()).hasSize(4); // Composition + DiagnosticReport + 2 Observations
    assertThat(bundle.getEntry()).allMatch(e -> e.getFullUrl().startsWith("urn:uuid:"));

    Composition composition = (Composition) bundle.getEntryFirstRep().getResource();
    assertThat(composition.getMeta().getProfile().get(0).getValue())
        .contains("BRResultadoExameLaboratorial");
    assertThat(composition.getSubject().getIdentifier().getSystem())
        .isEqualTo("http://rnds.saude.gov.br/fhir/r4/NamingSystem/cns");
    assertThat(composition.getAuthorFirstRep().getIdentifier().getValue()).isEqualTo("2222222");
    String reportUrl = bundle.getEntry().get(1).getFullUrl();
    assertThat(composition.getSectionFirstRep().getEntryFirstRep().getReference())
        .isEqualTo(reportUrl);

    DiagnosticReport report = (DiagnosticReport) bundle.getEntry().get(1).getResource();
    assertThat(report.getMeta().getProfile()).hasSize(1);
    assertThat(report.getMeta().getProfile().get(0).getValue())
        .contains("BRDiagnosticoLaboratorioClinico");
    assertThat(report.getStatus()).isEqualTo(DiagnosticReport.DiagnosticReportStatus.FINAL);
    assertThat(report.getCode().getCodingFirstRep().getCode()).isEqualTo("0202010473");
    assertThat(report.getSubject().getIdentifier().getValue()).isEqualTo(Fixtures.CNS);
    assertThat(report.getSubject().hasReference()).isFalse();
    assertThat(report.getPerformerFirstRep().getIdentifier().getSystem())
        .isEqualTo("http://rnds.saude.gov.br/fhir/r4/NamingSystem/cnes");
    assertThat(report.getPerformerFirstRep().getIdentifier().getValue()).isEqualTo("1234567");
    assertThat(report.getResult())
        .extracting(r -> r.getReference())
        .containsExactly(
            bundle.getEntry().get(2).getFullUrl(), bundle.getEntry().get(3).getFullUrl());
    // nada local: extensões/identificadores do SUS Nexus, basedOn, presentedForm (Binary interno)
    assertThat(report.getExtension()).isEmpty();
    assertThat(report.getIdentifier()).isEmpty();
    assertThat(report.hasBasedOn()).isFalse();
    assertThat(report.hasPresentedForm()).isFalse();
    assertThat(report.hasText()).isFalse();
    Observation obs2 = (Observation) bundle.getEntry().get(3).getResource();
    assertThat(obs2.getExtension()).isEmpty();
    assertThat(obs2.hasEncounter()).isFalse();
    assertThat(obs2.getMeta().getProfile().get(0).getValue()).contains("BRResultadoExame");
    // minimização: nome, nascimento e endereço do paciente nunca vão no Bundle
    String body = ehrPosts().get(0).getBodyAsString();
    assertThat(body)
        .doesNotContain("MARIA")
        .doesNotContain("1971-03-14")
        .doesNotContain("FLORES")
        .doesNotContain("sus-nexus.gov.br")
        .doesNotContain(Fixtures.CPF);

    RndsSubmission s = submission(eventId);
    assertThat(s.status()).isEqualTo(RndsSubmissionStatus.ACCEPTED);
    assertThat(s.protocol()).isEqualTo(LOCATION);
    assertThat(s.httpStatus()).isEqualTo(201);
    assertThat(s.attempts()).isEqualTo(1);
    assertThat(s.bundleSha256()).hasSize(64);
    assertThat(s.integrationMessageId()).startsWith("msg_");
    assertThat(ledger.findById(s.integrationMessageId()).orElseThrow().status())
        .isEqualTo(IntegrationMessageStatus.PUBLISHED);
    // resultado publicado no core (ledger espelho)
    wm().verify(
            postRequestedFor(urlEqualTo(MESSAGES))
                .withRequestBody(matchingJsonPath("$.status", equalTo("published")))
                .withRequestBody(matchingJsonPath("$.connector_id", equalTo("connector-rnds")))
                .withRequestBody(matchingJsonPath("$.source_record_id", equalTo(eventId)))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    assertThat(metrics.count("resultado-exame", "accepted")).isEqualTo(accepted + 1);
  }

  @Test
  void preValidacaoSemCnsNemCpfVaiParaDlqSemChamadaExterna() {
    fhir("Patient/" + Fixtures.PATIENT_ID, "/fhir/patient-no-ids.json");
    String eventId = nextEventId();

    Outcome outcome =
        dispatcher.handle(
            "resultado-exame", Fixtures.examResultEvent(eventId, "available", "final"));

    assertThat(outcome).isEqualTo(Outcome.DEAD_LETTERED);
    assertThat(ehrPosts()).isEmpty();
    wm().verify(0, getRequestedFor(urlEqualTo(AUTH)));
    RndsSubmission s = submission(eventId);
    assertThat(s.status()).isEqualTo(RndsSubmissionStatus.INVALID);
    assertThat(s.attempts()).isZero();
    assertThat(s.outcomeSummary()).contains("patient_identifier").contains("sem CNS nem CPF");
    assertThat(ledger.findById(s.integrationMessageId()).orElseThrow().status())
        .isEqualTo(IntegrationMessageStatus.DEAD_LETTERED);
    wm().verify(
            postRequestedFor(urlEqualTo(MESSAGES))
                .withRequestBody(matchingJsonPath("$.status", equalTo("dead_lettered")))
                .withRequestBody(
                    matchingJsonPath("$.dead_letter.reason", containing("patient_identifier"))));
  }

  @Test
  void tokenObtidoComCertificadoDeClienteMtlsEmCache() throws Exception {
    auth.invalidate();
    String first = auth.token();
    String second = auth.token();

    assertThat(first).isEqualTo("rnds-tok").isEqualTo(second);
    wm().verify(1, getRequestedFor(urlEqualTo(AUTH)));
    LoggedRequest req = wm().findAll(getRequestedFor(urlEqualTo(AUTH))).get(0);
    assertThat(req.getAbsoluteUrl()).startsWith("https://");
    assertThat(connector.authenticate().authenticated()).isTrue();
    assertThat(connector.healthCheck().details().get("certificate_subject"))
        .contains("MUNICIPIO TESTE SUS NEXUS");

    // sem certificado de cliente o servidor recusa o handshake
    Path certs = WireMockRndsResource.certs();
    java.security.KeyStore trust = java.security.KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(certs.resolve("client-truststore.p12"))) {
      trust.load(in, "changeit".toCharArray());
    }
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);
    SSLContext noClientCert = SSLContext.getInstance("TLS");
    noClientCert.init(null, tmf.getTrustManagers(), null);
    HttpClient anonymous = HttpClient.newBuilder().sslContext(noClientCert).build();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("https://localhost:" + wm().httpsPort() + AUTH)).build();
    assertThatThrownBy(() -> anonymous.send(request, HttpResponse.BodyHandlers.ofString()))
        .isInstanceOf(IOException.class);
    wm().verify(1, getRequestedFor(urlEqualTo(AUTH)));
  }

  @Test
  void rejeicao422ComOperationOutcomeVaiParaDlqSemRetryEMascaraPii() throws IOException {
    wm().stubFor(
            post(urlEqualTo(EHR))
                .willReturn(
                    aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/fhir+json")
                        .withBody(
                            """
                            {"resourceType":"OperationOutcome","issue":[{"severity":"error",
                             "code":"business-rule","details":{"coding":[{"code":"BR-0042"}]},
                             "diagnostics":"Paciente CNS 700123456789010 / CPF 123.456.789-09 sem vínculo"}]}
                            """)));
    String eventId = nextEventId();

    Outcome outcome =
        dispatcher.handle(
            "resultado-exame", Fixtures.examResultEvent(eventId, "available", "final"));

    assertThat(outcome).isEqualTo(Outcome.DEAD_LETTERED);
    assertThat(ehrPosts()).hasSize(1); // sem retry
    RndsSubmission s = submission(eventId);
    assertThat(s.status()).isEqualTo(RndsSubmissionStatus.REJECTED);
    assertThat(s.httpStatus()).isEqualTo(422);
    assertThat(s.outcomeSummary()).contains("error/business-rule BR-0042");
    assertThat(s.operationOutcome()).contains("OperationOutcome");
    // PII mascarada: resumo, OperationOutcome armazenado, ledger, DLQ e logs
    for (String text : List.of(s.outcomeSummary(), s.operationOutcome())) {
      assertThat(text).doesNotContain(Fixtures.CNS).doesNotContain("123.456.789-09");
    }
    assertThat(s.outcomeSummary()).contains("***********9010").contains("***.***.***-09");
    String lastError =
        ledger.findById(s.integrationMessageId()).orElseThrow().lastError().message();
    assertThat(lastError).doesNotContain(Fixtures.CNS).contains("BR-0042");
    List<String> captured;
    synchronized (logs) {
      captured = List.copyOf(logs);
    }
    assertThat(captured).anyMatch(l -> l.contains("rejeitado pela RNDS"));
    assertThat(captured).noneMatch(l -> l.contains(Fixtures.CNS) || l.contains("123.456.789-09"));
    try (Stream<Path> files = Files.walk(Path.of("target/test-dlq"))) {
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        assertThat(Files.readString(f)).doesNotContain(Fixtures.CNS);
      }
    }
    wm().verify(
            postRequestedFor(urlEqualTo(MESSAGES))
                .withRequestBody(matchingJsonPath("$.status", equalTo("dead_lettered"))));
  }

  @Test
  void erro503FazRetryExponencialEDepoisSucesso() {
    wm().stubFor(
            post(urlEqualTo(EHR))
                .inScenario("instavel")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ok"));
    wm().stubFor(
            post(urlEqualTo(EHR))
                .inScenario("instavel")
                .whenScenarioStateIs("ok")
                .willReturn(json(200, "{\"resourceType\":\"Bundle\",\"id\":\"prot-123\"}")));
    String eventId = nextEventId();
    double retries = metrics.count("resultado-exame", "retry");

    Outcome outcome =
        dispatcher.handle(
            "resultado-exame", Fixtures.examResultEvent(eventId, "available", "amended"));

    assertThat(outcome).isEqualTo(Outcome.ACCEPTED);
    assertThat(ehrPosts()).hasSize(2);
    RndsSubmission s = submission(eventId);
    assertThat(s.status()).isEqualTo(RndsSubmissionStatus.ACCEPTED);
    assertThat(s.protocol()).isEqualTo("prot-123"); // sem Location: id do corpo
    assertThat(s.attempts()).isEqualTo(2);
    assertThat(metrics.count("resultado-exame", "retry")).isEqualTo(retries + 1);
    // mesmo evento → mesmo Bundle (urn:uuid determinísticos)
    assertThat(ehrPosts().get(0).getBodyAsString().replaceAll("\"timestamp\":\"[^\"]+\"", ""))
        .isEqualTo(ehrPosts().get(1).getBodyAsString().replaceAll("\"timestamp\":\"[^\"]+\"", ""));
  }

  @Test
  void erro503PersistenteEsgotaRetryEVaiParaDlq() {
    wm().stubFor(post(urlEqualTo(EHR)).willReturn(aResponse().withStatus(503)));
    String eventId = nextEventId();

    Outcome outcome =
        dispatcher.handle(
            "resultado-exame", Fixtures.examResultEvent(eventId, "available", "final"));

    assertThat(outcome).isEqualTo(Outcome.DEAD_LETTERED);
    assertThat(ehrPosts()).hasSize(3); // connector.retry.max-attempts=3 em %test
    RndsSubmission s = submission(eventId);
    assertThat(s.status()).isEqualTo(RndsSubmissionStatus.FAILED);
    assertThat(s.httpStatus()).isEqualTo(503);
  }

  @Test
  void idempotenciaPorEventId() {
    String eventId = nextEventId();
    String event = Fixtures.examResultEvent(eventId, "available", "final");

    assertThat(dispatcher.handle("resultado-exame", event)).isEqualTo(Outcome.ACCEPTED);
    assertThat(dispatcher.handle("resultado-exame", event)).isEqualTo(Outcome.DUPLICATE);

    assertThat(ehrPosts()).hasSize(1);
    assertThat(metrics.ignoredCount("resultado-exame", "duplicate")).isGreaterThanOrEqualTo(1);
  }

  @Test
  void modeloDesabilitadoNaoEnvia() {
    // sumario-alta: rnds.models.sumario-alta.enabled=false em %test
    String eventId = nextEventId();

    Outcome outcome = dispatcher.handle("sumario-alta", Fixtures.dischargeEvent(eventId));

    assertThat(outcome).isEqualTo(Outcome.IGNORED_DISABLED);
    assertThat(submissions.findByEventId(eventId)).isEmpty();
    wm().verify(0, anyRequestedFor(urlPathMatching("/fhir/.*")));
    assertThat(ehrPosts()).isEmpty();
  }

  @Test
  void resultadoPreliminarNaoSeAplica() {
    String eventId = nextEventId();

    Outcome outcome =
        dispatcher.handle(
            "resultado-exame", Fixtures.examResultEvent(eventId, "available", "preliminary"));

    assertThat(outcome).isEqualTo(Outcome.IGNORED_NOT_APPLICABLE);
    assertThat(ehrPosts()).isEmpty();
  }

  @Test
  void gatilhoKafkaSusExamResult() {
    String eventId = nextEventId();

    bus.source("rnds-exam-result").send(Fixtures.examResultEvent(eventId, "available", "final"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                submissions
                    .findByEventId(eventId)
                    .map(s -> s.status() == RndsSubmissionStatus.ACCEPTED)
                    .orElse(false));
    assertThat(ehrPosts()).hasSize(1);
  }

  @Test
  void reconciliacaoEnviadosVersusAceitos() {
    String ok = nextEventId();
    dispatcher.handle("resultado-exame", Fixtures.examResultEvent(ok, "available", "final"));
    wm().stubFor(
            post(urlEqualTo(EHR))
                .willReturn(
                    json(
                        400,
                        "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\"}]}")));
    String rejected = nextEventId();
    dispatcher.handle("resultado-exame", Fixtures.examResultEvent(rejected, "available", "final"));

    ReconciliationReport report = routes.reconcile();

    ReconciliationReport.Entry entry =
        report.entries().stream()
            .filter(e -> e.entityType().equals("rnds_resultado_exame"))
            .findFirst()
            .orElseThrow();
    assertThat(entry.sourceCount()).isEqualTo(2);
    assertThat(entry.busCount()).isEqualTo(1);
    assertThat(entry.gap()).isEqualTo(1);
    wm().verify(
            postRequestedFor(urlEqualTo("/api/v1/integration/reconciliation"))
                .withRequestBody(matchingJsonPath("$.entity_type", equalTo("rnds_resultado_exame")))
                .withRequestBody(matchingJsonPath("$.gap", equalTo("1"))));
    Optional<RndsSubmission> r = submissions.findByEventId(rejected);
    assertThat(r.orElseThrow().status()).isEqualTo(RndsSubmissionStatus.REJECTED);

    ReconciliationReport empty =
        connector.reconcile(
            new Period(
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-02T00:00:00Z")));
    assertThat(empty.hasGap()).isFalse();
  }

  @Test
  void heartbeatPublicaStatusNoCore() {
    routes.heartbeat();

    wm().verify(
            postRequestedFor(urlEqualTo("/api/v1/integration/connectors/connector-rnds/heartbeat"))
                .withRequestBody(matchingJsonPath("$.source_system", equalTo("RNDS")))
                .withRequestBody(matchingJsonPath("$.health", equalTo("healthy")))
                .withRequestBody(
                    matchingJsonPath("$.metrics.resultado-exame.enabled", equalTo("true"))));
  }
}
