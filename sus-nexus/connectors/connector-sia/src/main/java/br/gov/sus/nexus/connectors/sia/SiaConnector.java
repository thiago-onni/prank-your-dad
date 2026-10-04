package br.gov.sus.nexus.connectors.sia;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import br.gov.sus.nexus.connectors.sdk.api.PublishResult;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.config.ConnectorConfig;
import br.gov.sus.nexus.connectors.sdk.events.IngestEnvelopes;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingEngine;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingLoader;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingVersion;
import br.gov.sus.nexus.connectors.sdk.reconcile.SourceCounter;
import br.gov.sus.nexus.connectors.sdk.runtime.AbstractConnector;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conector SIA/SIH (somente leitura de arquivos; nunca transmite ao DATASUS — a transmissão oficial
 * é feita pelo faturamento municipal no sistema oficial):
 *
 * <ul>
 *   <li>{@code production_record}: exportações de produção do sistema de origem (BPA-C, BPA-I,
 *       APAC, AIH) → {@code ProductionRecordRegistration}, mesma porta do {@code POST
 *       /production/records};
 *   <li>{@code production_outcome}: retornos de processamento SIA/SIH (rejeições/glosas, aceites,
 *       pagos) → {@code ProductionOutcomeRegistration}. Layouts marcados <b>A CONFIRMAR</b> até a
 *       homologação.
 * </ul>
 *
 * <p>Publica no tópico de ingestão {@code sus.ingest.production.v1} (consumidor do core {@code
 * ingest-production-in}, idempotente por {@code event_inbox}); {@code event_id} determinístico por
 * arquivo (SHA-256) + linha. Cada linha é uma {@code integration_message} (raw zone, ledger, DLQ,
 * espelho no core).
 */
@ApplicationScoped
public class SiaConnector extends AbstractConnector {

  public static final String CONNECTOR_ID = "connector-sia";
  public static final String SOURCE_SYSTEM = "SIA";
  public static final String JSON = "application/json";
  public static final String META_KIND = "kind";
  public static final String META_FILE = "file";
  public static final String META_FILE_SHA256 = "file_sha256";
  public static final String META_LINE = "line";
  public static final String EVENT_RECORD = "sus.ingest.production.record";
  public static final String EVENT_OUTCOME = "sus.ingest.production.outcome";

  static final String ATTR_EVENT_SEED = "event_seed";
  static final String ATTR_LAYOUT_STATUS = "layout_status";

  private static final Set<String> KINDS = Set.of("bpa_c", "bpa_i", "apac", "aih");
  private static final Set<String> CHARACTER =
      Set.of("elective", "urgency", "work_accident", "other");
  private static final Set<String> OUTCOMES =
      Set.of("transmitted", "received", "accepted", "rejected", "paid");

  private final SiaConfig config;
  private final ConnectorConfig connectorConfig;
  private final ObjectMapper mapper;
  private final ProductionEventPublisher publisher;
  private SiaLayout layout;
  private MappingVersion recordMapping;
  private MappingVersion outcomeMapping;
  private ConnectorDescriptor descriptor;
  private ProcessedFileRegistry registry;

  @Inject
  public SiaConnector(
      SiaConfig config,
      ConnectorConfig connectorConfig,
      ObjectMapper mapper,
      ProductionEventPublisher publisher) {
    this.config = config;
    this.connectorConfig = connectorConfig;
    this.mapper = mapper;
    this.publisher = publisher;
  }

  @PostConstruct
  void init() {
    this.layout = SiaLayout.load(config.layout());
    this.recordMapping = MappingLoader.fromClasspath(config.mapping().record());
    this.outcomeMapping = MappingLoader.fromClasspath(config.mapping().outcome());
    this.registry = new ProcessedFileRegistry(Path.of(config.file().processedRegistry()), mapper);
    this.descriptor =
        ConnectorDescriptor.builder()
            .connectorId(CONNECTOR_ID)
            .connectorVersion("0.1.0")
            .sourceSystem(SOURCE_SYSTEM)
            .supportedSourceVersions(List.of(config.sourceVersion()))
            .supportedProtocols(List.of("file-csv", "file-txt", "kafka"))
            .supportedEntities(
                List.of(CanonicalBatch.PRODUCTION_RECORD, CanonicalBatch.PRODUCTION_OUTCOME))
            .authenticationMethod(ConnectorDescriptor.AuthenticationMethod.FILE_SYSTEM)
            .requiredNetworkAccess(List.of("kafka:9093", "core-municipal:8080"))
            .dataClassification(ConnectorDescriptor.DataClassification.HIGHLY_RESTRICTED)
            .pollingOrEventMode(ConnectorDescriptor.IngestionMode.FILE_DROP)
            .retryPolicy(retryPolicy.toSpec())
            .rateLimitPolicy(new ConnectorDescriptor.RateLimitPolicy(1200, 1))
            .fieldMappingVersion(
                recordMapping.version() + "/" + outcomeMapping.version() + "/" + layout.version())
            .testSuiteVersion("1.0.0")
            .owner("equipe-integracao@sus-nexus")
            .supportSla(
                new ConnectorDescriptor.SupportSla("gold", Duration.ofHours(4), Duration.ofDays(2)))
            .build();
  }

  @Override
  public ConnectorDescriptor descriptor() {
    return descriptor;
  }

  public SiaLayout layout() {
    return layout;
  }

  public ProcessedFileRegistry registry() {
    return registry;
  }

  @Override
  public HealthStatus healthCheck() {
    Path production = Path.of(config.file().productionDir());
    Path returns = Path.of(config.file().returnsDir());
    Map<String, String> details = new LinkedHashMap<>();
    details.put("mode", "file");
    details.put("production_dir", production.toString());
    details.put("returns_dir", returns.toString());
    details.put("layout", layout.version());
    details.put("topic", config.publish().topic());
    long pending = layout.kinds().stream().filter(SiaLayout.Kind::pendingConfirmation).count();
    if (pending > 0) details.put("layouts_a_confirmar", String.valueOf(pending));
    if (!Files.isDirectory(production) || !Files.isDirectory(returns)) {
      return new HealthStatus(HealthStatus.State.DEGRADED, details);
    }
    return new HealthStatus(HealthStatus.State.HEALTHY, details);
  }

  // ------------------------------------------------------------------ transform

  @Override
  public CanonicalBatch transform(RawMessage raw) {
    JsonNode wrapper;
    try {
      wrapper = mapper.readTree(raw.content());
    } catch (IOException e) {
      throw ConnectorException.permanent("transform", "linha SIA inválida (JSON esperado)", e);
    }
    String kindName = wrapper.path("layout_kind").asText(null);
    if (kindName == null) {
      throw ConnectorException.permanent("transform", "mensagem sem layout_kind", null);
    }
    SiaLayout.Kind kind = layout.require(kindName);
    Map<String, String> fields = new LinkedHashMap<>();
    wrapper
        .path("fields")
        .fields()
        .forEachRemaining(e -> fields.put(e.getKey(), e.getValue().asText()));
    Map<String, String> canonical = kind.canonicalize(fields);
    String system = kind.sourceSystem() == null ? SOURCE_SYSTEM : kind.sourceSystem();
    if (CanonicalBatch.PRODUCTION_RECORD.equals(raw.entityType())
        && !canonical.getOrDefault("sistema_origem", "").isBlank()) {
      system = canonical.get("sistema_origem"); // coluna da exportação sobrepõe o layout
    }
    canonical.put("_source_system", system);
    SiaRules.applyImplicitDecimals(
        canonical, "valor_pago", canonical.get("valor_casas_implicitas"));
    canonical.put("_source_record_id", raw.sourceRecordId());
    canonical.put(
        "_source_record_version",
        raw.sourceRecordVersion() == null ? "" : raw.sourceRecordVersion());

    MappingVersion mapping;
    Map<String, Object> payload;
    switch (raw.entityType()) {
      case CanonicalBatch.PRODUCTION_RECORD -> {
        mapping = recordMapping;
        payload = MappingEngine.apply(mapping, canonical);
        Map<String, Object> ref =
            SiaRules.citizenRef(
                canonical.get("id_cidadao"),
                canonical.get("cns_paciente"),
                canonical.get("cpf_paciente"));
        if (ref != null) payload.put("citizen_ref", ref);
      }
      case CanonicalBatch.PRODUCTION_OUTCOME -> {
        mapping = outcomeMapping;
        payload = MappingEngine.apply(mapping, canonical);
        SiaRules.resolveOutcomeTarget(payload);
      }
      default ->
          throw ConnectorException.permanent(
              "transform", "entity_type não suportado: " + raw.entityType(), null);
    }
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("source_record_id", raw.sourceRecordId());
    attrs.put(
        ATTR_EVENT_SEED,
        String.join(
            "|",
            kind.name(),
            wrapper.path("file_sha256").asText(""),
            wrapper.path("line").asText("")));
    attrs.put(ATTR_LAYOUT_STATUS, kind.pendingConfirmation() ? "A CONFIRMAR" : kind.status());
    return new CanonicalBatch(
        raw.entityType(),
        mapping.version(),
        List.of(new CanonicalRecord(raw.sourceRecordId(), raw.sourceRecordVersion(), payload)),
        attrs);
  }

  // ------------------------------------------------------------------ validate

  @Override
  @SuppressWarnings("unchecked")
  public ValidationReport validate(CanonicalBatch batch) {
    ValidationReport.Builder b = ValidationReport.builder();
    for (CanonicalRecord r : batch.records()) {
      Map<String, Object> p = r.payload();
      String id = r.sourceRecordId();
      Map<String, Object> source = (Map<String, Object>) p.get("source");
      b.required(id, "source.system", source == null ? null : source.get("system"));
      b.required(
          id, "source.source_record_id", source == null ? null : source.get("source_record_id"));
      switch (batch.entityType()) {
        case CanonicalBatch.PRODUCTION_RECORD -> validateRecord(b, id, p);
        case CanonicalBatch.PRODUCTION_OUTCOME -> validateOutcome(b, id, p);
        default -> b.error(id, "entity_type", "unsupported", batch.entityType());
      }
    }
    return b.build();
  }

  @SuppressWarnings("unchecked")
  private static void validateRecord(ValidationReport.Builder b, String id, Map<String, Object> p) {
    b.required(id, "kind", p.get("kind"));
    b.required(id, "competence", p.get("competence"));
    b.required(id, "cnes", p.get("cnes"));
    b.required(id, "professional_cbo", p.get("professional_cbo"));
    b.required(id, "procedure_code", p.get("procedure_code"));
    b.required(id, "quantity", p.get("quantity"));
    b.required(id, "attendance_date", p.get("attendance_date"));
    Object kind = p.get("kind");
    if (kind != null && !KINDS.contains(kind.toString())) {
      b.error(id, "kind", "enum", "instrumento fora do domínio (bpa_c|bpa_i|apac|aih)");
    }
    format(b, id, "competence", p.get("competence"), "^[0-9]{4}(0[1-9]|1[0-2])$", "yyyyMM");
    format(b, id, "cnes", p.get("cnes"), "\\d{7}", "7 dígitos");
    format(b, id, "professional_cbo", p.get("professional_cbo"), "\\d{6}", "6 dígitos");
    format(b, id, "procedure_code", p.get("procedure_code"), "\\d{10}", "10 dígitos (SIGTAP)");
    Object qty = p.get("quantity");
    if (qty instanceof Number n && (n.intValue() < 1 || n.intValue() > 999_999)) {
      b.error(id, "quantity", "range", "quantidade deve estar entre 1 e 999999");
    }
    Object pcns = p.get("professional_cns");
    if (pcns != null && !SiaRules.validCns(pcns.toString())) {
      b.error(id, "professional_cns", "format", "CNS do profissional inválido");
    }
    Object character = p.get("character_of_care");
    if (character != null && !CHARACTER.contains(character.toString())) {
      b.error(id, "character_of_care", "enum", "caráter de atendimento fora do domínio");
    }
    length(b, id, "apac_number", p.get("apac_number"), 13);
    length(b, id, "aih_number", p.get("aih_number"), 13);
    if ("apac".equals(kind) && p.get("apac_number") == null) {
      b.error(id, "apac_number", "required", "APAC exige número da autorização");
    }
    if ("aih".equals(kind) && p.get("aih_number") == null) {
      b.error(id, "aih_number", "required", "AIH exige número da autorização");
    }
    Map<String, Object> ref = (Map<String, Object>) p.get("citizen_ref");
    boolean individualized = kind != null && !"bpa_c".equals(kind.toString());
    if (individualized && ref == null) {
      b.error(id, "citizen_ref", "required", "BPA-I/APAC/AIH exigem cidadão identificado");
    }
    if (ref != null && ref.get("identifier_system") != null) {
      String system = ref.get("identifier_system").toString();
      String value = String.valueOf(ref.get("identifier_value"));
      if ("CNS".equals(system) && !SiaRules.validCns(value)) {
        b.error(id, "citizen_ref", "format", "CNS do cidadão inválido");
      }
      if ("CPF".equals(system) && !SiaRules.validCpf(value)) {
        b.error(id, "citizen_ref", "format", "CPF do cidadão inválido");
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static void validateOutcome(
      ValidationReport.Builder b, String id, Map<String, Object> p) {
    b.required(id, "outcome", p.get("outcome"));
    b.required(id, "processed_at", p.get("processed_at"));
    Object outcome = p.get("outcome");
    if (outcome != null && !OUTCOMES.contains(outcome.toString())) {
      b.error(id, "outcome", "enum", "situação de retorno fora do domínio do core");
    }
    boolean record = p.get("production_record_id") != null;
    Map<String, Object> source = (Map<String, Object>) p.get("record_source");
    boolean bySource = source != null && source.get("source_record_id") != null;
    boolean batch = p.get("batch_id") != null;
    if (!record && !bySource && !batch) {
      b.error(
          id,
          "target",
          "required",
          "retorno sem alvo: informe id do barramento (prod_), registro de origem ou lote");
    }
    if (record && !SiaRules.PROD_ID.matcher(p.get("production_record_id").toString()).matches()) {
      b.error(id, "production_record_id", "format", "id do registro deve ser prod_<ULID>");
    }
    if (bySource && (source.get("system") == null || source.get("system").toString().isBlank())) {
      b.error(id, "record_source.system", "required", "sistema do registro de origem ausente");
    }
    if ("paid".equals(outcome)) {
      if (p.get("paid_amount") == null) {
        b.error(id, "paid_amount", "required", "pagamento exige valor pago");
      }
      if (batch) b.error(id, "batch_id", "target", "pagamento exige registro (não lote)");
    }
    Object paid = p.get("paid_amount");
    if (paid instanceof BigDecimal d && d.signum() < 0) {
      b.error(id, "paid_amount", "range", "valor pago negativo");
    }
    length(b, id, "reason_code", p.get("reason_code"), 32);
    length(b, id, "reason", p.get("reason"), 500);
    length(b, id, "protocol_number", p.get("protocol_number"), 64);
  }

  private static void format(
      ValidationReport.Builder b, String id, String field, Object v, String regex, String hint) {
    if (v != null && !v.toString().matches(regex)) {
      b.error(id, field, "format", field + " deve ser " + hint);
    }
  }

  private static void length(
      ValidationReport.Builder b, String id, String field, Object v, int max) {
    if (v != null && v.toString().length() > max) {
      b.error(id, field, "length", field + " excede " + max + " caracteres");
    }
  }

  // ------------------------------------------------------------------ publish

  /**
   * Publica cada registro como envelope em {@code sus.ingest.production.v1} (não usa o {@code
   * CorePublisher} REST). {@code event_id} determinístico (arquivo + linha): reenvio ou
   * reprocessamento é descartado pelo {@code event_inbox} do core.
   */
  @Override
  @SuppressWarnings("unchecked")
  public PublishResult publish(CanonicalBatch batch) {
    String correlationId =
        batch.attributes().getOrDefault(CanonicalBatch.CORRELATION_ID, Ids.correlation());
    boolean replay = "true".equals(batch.attributes().get(CanonicalBatch.REPROCESS));
    String eventType =
        CanonicalBatch.PRODUCTION_OUTCOME.equals(batch.entityType()) ? EVENT_OUTCOME : EVENT_RECORD;
    List<String> eventIds = new ArrayList<>();
    for (CanonicalRecord r : batch.records()) {
      Map<String, Object> source = (Map<String, Object>) r.payload().get("source");
      String key = String.valueOf(source.get("source_record_id"));
      String eventId =
          IngestEnvelopes.eventId(
              CONNECTOR_ID,
              batch.entityType(),
              batch.attributes().getOrDefault(ATTR_EVENT_SEED, key));
      IngestEnvelopes.Spec spec =
          new IngestEnvelopes.Spec(
              eventId,
              eventType,
              connectorConfig.tenantId(),
              envelopeSource(source),
              r.payload(),
              "highly_restricted",
              List.of("production_audit"),
              correlationId,
              null,
              occurredAt(batch.entityType(), r.payload()),
              replay);
      String json;
      try {
        json = mapper.writeValueAsString(IngestEnvelopes.envelope(spec));
      } catch (JsonProcessingException e) {
        throw ConnectorException.permanent("publish", "falha ao serializar envelope", e);
      }
      publisher.publish(key, json, IngestEnvelopes.headers(spec, CONNECTOR_ID));
      eventIds.add(eventId);
    }
    return new PublishResult(batch.size(), 0, eventIds, config.publish().topic());
  }

  /** {@code source} do envelope: só os campos do contrato ({@code cnes} quando 7 dígitos). */
  private static Map<String, Object> envelopeSource(Map<String, Object> source) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("system", source.get("system"));
    out.put("connector", CONNECTOR_ID);
    out.put("source_record_id", source.get("source_record_id"));
    if (source.get("source_record_version") != null) {
      out.put("source_record_version", source.get("source_record_version"));
    }
    Object cnes = source.get("cnes");
    if (cnes != null && cnes.toString().matches("\\d{7}")) out.put("cnes", cnes);
    return out;
  }

  private static OffsetDateTime occurredAt(String entity, Map<String, Object> payload) {
    if (CanonicalBatch.PRODUCTION_OUTCOME.equals(entity) && payload.get("processed_at") != null) {
      try {
        return OffsetDateTime.parse(payload.get("processed_at").toString());
      } catch (RuntimeException e) {
        return null;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ reconciliação

  /**
   * Fonte = linhas lidas por entidade em arquivos processados no período (registro de arquivos).
   */
  @Override
  protected SourceCounter sourceCounter() {
    return registry::rows;
  }
}
