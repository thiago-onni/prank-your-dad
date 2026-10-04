package br.gov.sus.nexus.connectors.rnds;

import br.gov.sus.nexus.connectors.rnds.RndsEhrClient.EhrResponse;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmission;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStatus;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStore;
import br.gov.sus.nexus.connectors.sdk.api.AuthResult;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import br.gov.sus.nexus.connectors.sdk.api.Period;
import br.gov.sus.nexus.connectors.sdk.api.PublishResult;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ReconciliationReport;
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.runtime.AbstractConnector;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.hl7.fhir.r4.formats.JsonParser;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;
import org.jboss.logging.Logger;

/**
 * Conector de <b>saída</b> para a RNDS. Pipeline do SDK com a mensagem bruta = envelope do evento
 * do barramento ({@code sus.exam.result.v1} / {@code sus.hospital.discharge.v1}):
 *
 * <ul>
 *   <li>{@link #transform}: lê os recursos no fhir-gateway e monta o Bundle do modelo ({@link
 *       BundleAssembler} + YAML versionado);
 *   <li>{@link #validate}: pré-validação declarativa (falha → DLQ, nenhuma chamada à RNDS);
 *   <li>{@link #publish}: token mTLS + POST ao EHR; 2xx aceito (protocolo), 4xx rejeitado ({@code
 *       OperationOutcome} armazenado, DLQ sem retry), 5xx/timeout transitório (retry exponencial).
 * </ul>
 */
@ApplicationScoped
public class RndsConnector extends AbstractConnector {

  public static final String CONNECTOR_ID = "connector-rnds";
  public static final String SOURCE_SYSTEM = "RNDS";
  public static final String META_MODEL = "model";
  static final String ASSEMBLED = "assembled";
  private static final int MAX_OUTCOME_CHARS = 32_000;

  private static final Logger LOG = Logger.getLogger(RndsConnector.class);

  private final RndsConfig config;
  private final ObjectMapper mapper;
  private final ResourceFetcher fetcher;
  private final RndsAuthClient auth;
  private final RndsEhrClient ehr;
  private final RndsSubmissionStore submissions;
  private final RndsMetrics rndsMetrics;
  private final Map<String, ModelMapping> mappings = new TreeMap<>();
  private ConnectorDescriptor descriptor;

  @Inject
  public RndsConnector(
      RndsConfig config,
      ObjectMapper mapper,
      ResourceFetcher fetcher,
      RndsAuthClient auth,
      RndsEhrClient ehr,
      RndsSubmissionStore submissions,
      RndsMetrics rndsMetrics) {
    this.config = config;
    this.mapper = mapper;
    this.fetcher = fetcher;
    this.auth = auth;
    this.ehr = ehr;
    this.submissions = submissions;
    this.rndsMetrics = rndsMetrics;
  }

  @PostConstruct
  void init() {
    config
        .models()
        .forEach(
            (model, cfg) -> {
              ModelMapping mapping = ModelMappingLoader.load(cfg.mapping());
              if (!mapping.model().equals(model)) {
                throw new IllegalStateException(
                    "rnds.models." + model + ".mapping aponta para o modelo " + mapping.model());
              }
              String type = cfg.bundleType().orElse(mapping.bundle().type());
              if (!ModelMapping.BUNDLE_TYPES.contains(type)) {
                throw new IllegalStateException("rnds.models." + model + ".bundle-type inválido");
              }
              mappings.put(model, mapping);
            });
    if (mappings.isEmpty()) {
      throw new IllegalStateException("nenhum modelo RNDS configurado (rnds.models.*)");
    }
    List<String> entities = mappings.keySet().stream().map(RndsConnector::entityType).toList();
    String versions =
        String.join(
            ",",
            mappings.values().stream()
                .map(m -> "rnds-" + m.model() + "-" + m.version() + ".yaml")
                .toList());
    this.descriptor =
        ConnectorDescriptor.builder()
            .connectorId(CONNECTOR_ID)
            .connectorVersion("0.1.0")
            .sourceSystem(SOURCE_SYSTEM)
            .supportedSourceVersions(List.of(config.sourceVersion()))
            .supportedProtocols(
                List.of(
                    "kafka (sus.exam.result.v1, sus.hospital.discharge.v1)",
                    "https-fhir-r4 (fhir-gateway, oauth2 client credentials system/*.read)",
                    "https-fhir-r4 (RNDS EHR, mTLS ICP-Brasil e-CNPJ)"))
            .supportedEntities(entities)
            .authenticationMethod(ConnectorDescriptor.AuthenticationMethod.MTLS)
            .requiredNetworkAccess(
                List.of(
                    "kafka:9092",
                    "fhir-gateway (" + config.fhir().baseUrl() + ")",
                    "core-municipal:8080",
                    "RNDS auth (" + config.authUrl() + ")",
                    "RNDS EHR (" + config.ehrUrl() + ")"))
            .dataClassification(ConnectorDescriptor.DataClassification.HIGHLY_RESTRICTED)
            .pollingOrEventMode(ConnectorDescriptor.IngestionMode.EVENT)
            .retryPolicy(retryPolicy.toSpec())
            .rateLimitPolicy(new ConnectorDescriptor.RateLimitPolicy(120, 1))
            .fieldMappingVersion(versions)
            .testSuiteVersion("1.0.0")
            .owner("equipe-integracao@sus-nexus")
            .supportSla(
                new ConnectorDescriptor.SupportSla("gold", Duration.ofHours(2), Duration.ofDays(1)))
            .build();
  }

  public static String entityType(String model) {
    return "rnds_" + model.replace('-', '_');
  }

  @Override
  public ConnectorDescriptor descriptor() {
    return descriptor;
  }

  public Optional<ModelMapping> mapping(String model) {
    return Optional.ofNullable(mappings.get(model));
  }

  public boolean enabled(String model) {
    RndsConfig.Model cfg = config.models().get(model);
    return cfg != null && cfg.enabled() && mappings.containsKey(model);
  }

  public List<String> models() {
    return List.copyOf(mappings.keySet());
  }

  @Override
  public AuthResult authenticate() {
    try {
      auth.token();
      return AuthResult.ok("RNDS (certificado ICP-Brasil)");
    } catch (ConnectorException e) {
      return AuthResult.failed(Pii.maskText(e.getMessage()));
    }
  }

  @Override
  public HealthStatus healthCheck() {
    Map<String, String> details = new LinkedHashMap<>();
    List<String> enabled = mappings.keySet().stream().filter(this::enabled).toList();
    details.put("models_enabled", enabled.isEmpty() ? "nenhum" : String.join(",", enabled));
    details.put("requester_configured", String.valueOf(config.requesterCpf().isPresent()));
    Optional<MtlsSupport.CertificateInfo> cert = MtlsSupport.describe(config.certificate());
    cert.ifPresent(
        c -> {
          details.put("certificate_subject", c.subject());
          details.put("certificate_not_after", c.notAfter().toString());
        });
    if (enabled.isEmpty()) return new HealthStatus(HealthStatus.State.HEALTHY, details);
    if (cert.isEmpty()) {
      details.put("reason", "certificado ICP-Brasil ausente ou ilegível");
      return new HealthStatus(HealthStatus.State.DOWN, details);
    }
    if (cert.get().notAfter().isBefore(Instant.now().plus(Duration.ofDays(30)))) {
      details.put("reason", "certificado expira em menos de 30 dias");
      return new HealthStatus(HealthStatus.State.DEGRADED, details);
    }
    if (config.requesterCpf().isEmpty()) {
      details.put("reason", "rnds.requester-cpf não configurado");
      return new HealthStatus(HealthStatus.State.DEGRADED, details);
    }
    return new HealthStatus(HealthStatus.State.HEALTHY, details);
  }

  // ------------------------------------------------------------------ transform

  @Override
  public CanonicalBatch transform(RawMessage raw) {
    String model = raw.metadata().get(META_MODEL);
    ModelMapping mapping =
        mapping(model)
            .orElseThrow(
                () -> ConnectorException.permanent("transform", "modelo desconhecido", null));
    JsonNode envelope;
    try {
      envelope = mapper.readTree(raw.content());
    } catch (IOException e) {
      throw ConnectorException.permanent("transform", "envelope JSON inválido", e);
    }
    Map<String, String> eventData = eventData(envelope.path("data"));
    String stableId = stableId(model, eventData);
    String cnesSystem = mapping.identifierSystems().cnes();
    SourceResources src =
        switch (model) {
          case BundleAssembler.RESULTADO_EXAME ->
              fetcher.examResult(stableId, eventData, cnesSystem);
          case BundleAssembler.SUMARIO_ALTA -> fetcher.discharge(stableId, eventData, cnesSystem);
          default -> throw ConnectorException.permanent("transform", "modelo sem busca", null);
        };
    BundleAssembler.Context ctx =
        new BundleAssembler.Context(
            raw.sourceRecordId(),
            config.models().get(model).bundleType().orElse(mapping.bundle().type()),
            config.solicitanteId().orElse(null),
            config.cnesSolicitante().orElse(null),
            Instant.now());
    AssembledBundle assembled;
    try {
      assembled = BundleAssembler.assemble(mapping, src, ctx);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.permanent("transform", e.getMessage(), e);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(ASSEMBLED, assembled);
    payload.put("model", model);
    payload.put("source_id", stableId);
    return new CanonicalBatch(
        entityType(model),
        mapping.version(),
        List.of(new CanonicalRecord(raw.sourceRecordId(), null, payload)),
        Map.of("model", model, "event_id", raw.sourceRecordId()));
  }

  static String stableId(String model, Map<String, String> data) {
    String id =
        BundleAssembler.RESULTADO_EXAME.equals(model)
            ? data.get("exam_result_id")
            : data.get("hospital_episode_id");
    if (id == null || id.isBlank()) {
      throw ConnectorException.permanent("transform", "evento sem id do registro de origem", null);
    }
    return id;
  }

  /** Campos escalares de {@code data} (+ {@code principal_diagnosis_code}). */
  static Map<String, String> eventData(JsonNode data) {
    Map<String, String> out = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> it = data.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      if (e.getValue().isValueNode() && !e.getValue().isNull()) {
        out.put(e.getKey(), e.getValue().asText());
      }
    }
    JsonNode dx = data.path("principal_diagnosis").path("code");
    if (dx.isTextual()) out.put("principal_diagnosis_code", dx.asText());
    return out;
  }

  // ------------------------------------------------------------------ validate

  @Override
  public ValidationReport validate(CanonicalBatch batch) {
    ValidationReport.Builder all = ValidationReport.builder();
    for (CanonicalRecord r : batch.records()) {
      AssembledBundle a = (AssembledBundle) r.payload().get(ASSEMBLED);
      ModelMapping mapping = mappings.get(a.model());
      ValidationReport report = PreValidator.validate(mapping, a, r.sourceRecordId());
      report.issues().forEach(i -> addIssue(all, i));
      if (!report.isValid()) {
        String summary =
            "pré-validação: "
                + String.join(
                    "; ", report.errors().stream().map(ValidationReport.Issue::message).toList());
        submissions
            .findByEventId(r.sourceRecordId())
            .ifPresent(s -> submissions.save(s.withStatus(RndsSubmissionStatus.INVALID, summary)));
        rndsMetrics.submission(a.model(), "invalid");
        LOG.warnf("evento %s (%s) reprovado — %s", r.sourceRecordId(), a.model(), summary);
      }
    }
    return all.build();
  }

  private static void addIssue(ValidationReport.Builder b, ValidationReport.Issue i) {
    if (i.severity() == ValidationReport.Severity.ERROR) {
      b.error(i.sourceRecordId(), i.field(), i.code(), i.message());
    } else {
      b.warning(i.sourceRecordId(), i.field(), i.code(), i.message());
    }
  }

  // ------------------------------------------------------------------ publish

  @Override
  public PublishResult publish(CanonicalBatch batch) {
    List<String> protocols = new ArrayList<>();
    for (CanonicalRecord r : batch.records()) {
      protocols.add(send(r));
    }
    return new PublishResult(protocols.size(), 0, protocols, "rnds");
  }

  private String send(CanonicalRecord record) {
    AssembledBundle a = (AssembledBundle) record.payload().get(ASSEMBLED);
    String eventId = record.sourceRecordId();
    String model = a.model();
    String json;
    try {
      json = new JsonParser().composeString(a.bundle());
    } catch (IOException e) {
      throw ConnectorException.permanent("publish", "falha ao serializar o Bundle", e);
    }
    String sha = Hashes.sha256Hex(json);
    RndsSubmission submission =
        submissions
            .findByEventId(eventId)
            .orElseGet(() -> RndsSubmission.pending(eventId, model, a.stableId()))
            .attempt(sha);
    submissions.save(submission);
    long started = System.nanoTime();
    EhrResponse response;
    try {
      response = ehr.post(config.models().get(model).ehrPath(), json);
    } catch (ConnectorException e) {
      submissions.save(
          submission.withStatus(
              e.isTransient() ? RndsSubmissionStatus.RETRYING : RndsSubmissionStatus.FAILED,
              Pii.maskText(e.getMessage())));
      if (e.isTransient()) rndsMetrics.submission(model, "retry");
      throw e;
    } finally {
      rndsMetrics.latency(model, Duration.ofNanos(System.nanoTime() - started));
    }
    int status = response.status();
    if (status / 100 == 2) {
      String protocol = response.location().orElseGet(() -> idFromBody(response.body()));
      submissions.save(
          submission.withResponse(
              RndsSubmissionStatus.ACCEPTED, status, protocol, "aceito HTTP " + status, null));
      rndsMetrics.submission(model, "accepted");
      LOG.infof(
          "evento %s (%s) aceito pela RNDS: HTTP %d, tentativa %d",
          eventId, model, status, submission.attempts());
      return protocol == null ? "" : protocol;
    }
    if (status == 401) {
      auth.invalidate();
    }
    if (status == 401 || status == 408 || status == 429 || status >= 500) {
      String summary = "HTTP " + status + " (transitório)";
      submissions.save(
          submission.withResponse(RndsSubmissionStatus.RETRYING, status, null, summary, null));
      rndsMetrics.submission(model, "retry");
      throw ConnectorException.transientError("publish", "RNDS respondeu " + status, null);
    }
    String summary = "HTTP " + status + ": " + outcomeSummary(response.body());
    String outcome = Pii.maskText(truncate(response.body(), MAX_OUTCOME_CHARS));
    submissions.save(
        submission.withResponse(RndsSubmissionStatus.REJECTED, status, null, summary, outcome));
    rndsMetrics.submission(model, "rejected");
    LOG.warnf("evento %s (%s) rejeitado pela RNDS: %s", eventId, model, Pii.maskText(summary));
    throw ConnectorException.permanent("publish", "RNDS rejeitou o Bundle: " + summary, null);
  }

  private String idFromBody(String body) {
    if (body == null || body.isBlank()) return null;
    try {
      JsonNode node = mapper.readTree(body);
      return node.hasNonNull("id") ? node.get("id").asText() : null;
    } catch (IOException e) {
      return null;
    }
  }

  /** Resumo mascarado do {@code OperationOutcome} (severidade/código/diagnóstico truncado). */
  static String outcomeSummary(String body) {
    if (body == null || body.isBlank()) return "sem corpo";
    try {
      Resource r = new JsonParser().parse(body);
      if (r instanceof OperationOutcome oo && oo.hasIssue()) {
        List<String> parts = new ArrayList<>();
        for (OperationOutcome.OperationOutcomeIssueComponent issue : oo.getIssue()) {
          if (parts.size() >= 5) break;
          StringBuilder sb = new StringBuilder();
          sb.append(issue.getSeverity() == null ? "?" : issue.getSeverity().toCode())
              .append('/')
              .append(issue.getCode() == null ? "?" : issue.getCode().toCode());
          if (issue.hasDetails() && issue.getDetails().hasCoding()) {
            sb.append(' ').append(issue.getDetails().getCodingFirstRep().getCode());
          }
          if (issue.hasDiagnostics()) {
            sb.append(": ").append(truncate(issue.getDiagnostics(), 200));
          }
          parts.add(sb.toString());
        }
        return Pii.maskText(String.join(" | ", parts));
      }
      return "corpo sem OperationOutcome";
    } catch (IOException | RuntimeException e) {
      return Pii.maskText(truncate(body, 200));
    }
  }

  private static String truncate(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
  }

  // ------------------------------------------------------------------ reconcile

  @Override
  public ReconciliationReport reconcile(Period period) {
    return new RndsReconciliationJob(this, submissions, metrics).run(period);
  }
}
