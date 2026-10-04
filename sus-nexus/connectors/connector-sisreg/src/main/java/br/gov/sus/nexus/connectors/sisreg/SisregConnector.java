package br.gov.sus.nexus.connectors.sisreg;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingEngine;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingLoader;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingVersion;
import br.gov.sus.nexus.connectors.sdk.runtime.AbstractConnector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conector SISREG (plano B oficial): ingere exportações CSV/XLSX geradas pelo gestor municipal no
 * SISREG III (solicitações ambulatoriais, agendamentos, devoluções e oferta de vagas). Nunca
 * escreve no SISREG. Cada linha vira uma mensagem (raw zone, ledger e DLQ por registro).
 *
 * <ul>
 *   <li>{@code regulation_request} → {@code POST /regulation/requests} (upsert por código da
 *       solicitação, com o status atual);
 *   <li>{@code regulation_status} → {@code POST
 *       /regulation/requests/by-source/SISREG/{codigo}/status} (arquivos que só trazem a mudança:
 *       agendamentos/devoluções);
 *   <li>{@code provider_capacity} → {@code POST /regulation/capacity} (oferta por
 *       executante/procedimento/competência).
 * </ul>
 */
@ApplicationScoped
public class SisregConnector extends AbstractConnector {

  public static final String SOURCE_SYSTEM = "SISREG";
  public static final String META_FILE = "file";
  public static final String META_KIND = "kind";
  public static final String META_FILE_SHA256 = "file_sha256";
  public static final String JSON = "application/json";

  private static final Set<String> STATUSES =
      Set.of(
          "requested",
          "pending_documents",
          "returned",
          "under_review",
          "authorized",
          "denied",
          "scheduled",
          "cancelled",
          "no_show",
          "performed",
          "expired");
  private static final Set<String> PRIORITIES =
      Set.of("elective", "priority", "urgent", "emergency");

  private final SisregConfig config;
  private final ObjectMapper mapper;
  private SisregLayout layout;
  private MappingVersion requestMapping;
  private MappingVersion statusMapping;
  private MappingVersion capacityMapping;
  private ConnectorDescriptor descriptor;

  @Inject
  public SisregConnector(SisregConfig config, ObjectMapper mapper) {
    this.config = config;
    this.mapper = mapper;
  }

  @PostConstruct
  void init() {
    this.layout = SisregLayout.load(config.layout());
    this.requestMapping = MappingLoader.fromClasspath(config.mapping().request());
    this.statusMapping = MappingLoader.fromClasspath(config.mapping().status());
    this.capacityMapping = MappingLoader.fromClasspath(config.mapping().capacity());
    this.descriptor =
        ConnectorDescriptor.builder()
            .connectorId("connector-sisreg")
            .connectorVersion("0.1.0")
            .sourceSystem(SOURCE_SYSTEM)
            .supportedSourceVersions(List.of(config.sourceVersion()))
            .supportedProtocols(List.of("file-csv", "file-xlsx"))
            .supportedEntities(
                List.of(
                    CanonicalBatch.REGULATION_REQUEST,
                    CanonicalBatch.REGULATION_STATUS,
                    CanonicalBatch.PROVIDER_CAPACITY))
            .authenticationMethod(ConnectorDescriptor.AuthenticationMethod.FILE_SYSTEM)
            .requiredNetworkAccess(List.of("core-municipal:8080"))
            .dataClassification(ConnectorDescriptor.DataClassification.HIGHLY_RESTRICTED)
            .pollingOrEventMode(ConnectorDescriptor.IngestionMode.FILE_DROP)
            .rateLimitPolicy(new ConnectorDescriptor.RateLimitPolicy(600, 2))
            .fieldMappingVersion(requestMapping.version())
            .testSuiteVersion("1.0.0")
            .owner("equipe-integracao@sus-nexus")
            .supportSla(
                new ConnectorDescriptor.SupportSla("gold", Duration.ofHours(2), Duration.ofDays(1)))
            .build();
  }

  @Override
  public ConnectorDescriptor descriptor() {
    return descriptor;
  }

  public SisregLayout layout() {
    return layout;
  }

  @Override
  public HealthStatus healthCheck() {
    Path in = Path.of(config.file().inputDir());
    return Files.isDirectory(in)
        ? new HealthStatus(
            HealthStatus.State.HEALTHY,
            Map.of("mode", "file", "input_dir", in.toString(), "layout", layout.version()))
        : HealthStatus.degraded("diretório de entrada inexistente: " + in);
  }

  @Override
  public CanonicalBatch transform(RawMessage raw) {
    Map<String, String> row = readRow(raw);
    String kindName = raw.metadata().get(META_KIND);
    if (kindName == null) {
      throw ConnectorException.permanent("transform", "mensagem sem metadado kind", null);
    }
    SisregLayout.Kind kind = layout.require(kindName);
    Map<String, String> canonical = kind.canonicalize(row);
    Map<String, Object> payload;
    MappingVersion mapping;
    switch (raw.entityType()) {
      case CanonicalBatch.REGULATION_REQUEST -> {
        mapping = requestMapping;
        payload = MappingEngine.apply(mapping, canonical);
        payload.put(
            "kind", SisregRules.kind(canonical.get("tipo"), canonical.get("codigo_procedimento")));
        payload.put(
            "citizen_ref",
            SisregRules.citizenRef(
                canonical.get("cns_paciente"),
                canonical.get("cpf_paciente"),
                canonical.get("codigo_paciente")));
      }
      case CanonicalBatch.REGULATION_STATUS -> {
        mapping = statusMapping;
        payload = MappingEngine.apply(mapping, canonical);
      }
      case CanonicalBatch.PROVIDER_CAPACITY -> {
        mapping = capacityMapping;
        payload = MappingEngine.apply(mapping, canonical);
      }
      default ->
          throw ConnectorException.permanent(
              "transform", "entity_type não suportado: " + raw.entityType(), null);
    }
    return new CanonicalBatch(
        raw.entityType(),
        mapping.version(),
        List.of(new CanonicalRecord(raw.sourceRecordId(), raw.sourceRecordVersion(), payload)),
        Map.of("source_record_id", raw.sourceRecordId()));
  }

  private Map<String, String> readRow(RawMessage raw) {
    try {
      return mapper.readValue(raw.content(), new TypeReference<Map<String, String>>() {});
    } catch (IOException e) {
      throw new UncheckedIOException("linha SISREG inválida (JSON esperado)", e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public ValidationReport validate(CanonicalBatch batch) {
    ValidationReport.Builder b = ValidationReport.builder();
    for (CanonicalRecord r : batch.records()) {
      Map<String, Object> p = r.payload();
      String id = r.sourceRecordId();
      switch (batch.entityType()) {
        case CanonicalBatch.REGULATION_REQUEST -> {
          Map<String, Object> source = (Map<String, Object>) p.get("source");
          b.required(
              id,
              "source.source_record_id",
              source == null ? null : source.get("source_record_id"));
          b.required(id, "status", p.get("status"));
          b.required(id, "kind", p.get("kind"));
          b.required(id, "requested_at", p.get("requested_at"));
          b.required(id, "requested_service_code", p.get("requested_service_code"));
          Map<String, Object> ref = (Map<String, Object>) p.get("citizen_ref");
          b.required(
              id, "citizen_ref.identifier_value", ref == null ? null : ref.get("identifier_value"));
          checkEnum(b, id, "status", p.get("status"), STATUSES);
          checkEnum(b, id, "priority", p.get("priority"), PRIORITIES);
          checkCnes(b, id, "requesting_cnes", p.get("requesting_cnes"));
          checkCnes(b, id, "provider_cnes", p.get("provider_cnes"));
          if (ref != null
              && "CNS".equals(ref.get("identifier_system"))
              && !String.valueOf(ref.get("identifier_value")).matches("\\d{15}")) {
            b.error(id, "citizen_ref", "format", "CNS deve ter 15 dígitos");
          }
        }
        case CanonicalBatch.REGULATION_STATUS -> {
          Map<String, Object> target = (Map<String, Object>) p.get("target_ref");
          b.required(
              id,
              "target_ref.source_record_id",
              target == null ? null : target.get("source_record_id"));
          b.required(id, "status", p.get("status"));
          b.required(id, "occurred_at", p.get("occurred_at"));
          checkEnum(b, id, "status", p.get("status"), STATUSES);
          checkCnes(b, id, "provider_cnes", p.get("provider_cnes"));
        }
        case CanonicalBatch.PROVIDER_CAPACITY -> {
          b.required(id, "provider_cnes", p.get("provider_cnes"));
          b.required(id, "service_code", p.get("service_code"));
          b.required(id, "competence", p.get("competence"));
          b.required(id, "offered", p.get("offered"));
          checkCnes(b, id, "provider_cnes", p.get("provider_cnes"));
          if (p.get("competence") != null && !p.get("competence").toString().matches("\\d{6}")) {
            b.error(id, "competence", "format", "competência deve ser yyyyMM");
          }
        }
        default -> b.error(id, "entity_type", "unsupported", batch.entityType());
      }
    }
    return b.build();
  }

  private static void checkEnum(
      ValidationReport.Builder b, String id, String field, Object value, Set<String> allowed) {
    if (value != null && !allowed.contains(value.toString())) {
      b.error(id, field, "enum", field + " fora do domínio do core: " + value);
    }
  }

  private static void checkCnes(ValidationReport.Builder b, String id, String field, Object value) {
    if (value != null && !value.toString().matches("\\d{7}")) {
      b.error(id, field, "format", field + " deve ter 7 dígitos");
    }
  }
}
