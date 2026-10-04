package br.gov.sus.nexus.connectors.esusreg;

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
import br.gov.sus.nexus.connectors.sdk.parse.JsonFlattener;
import br.gov.sus.nexus.connectors.sdk.runtime.AbstractConnector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conector e-SUS Regulação. Modo {@code api}: cliente REST genérico (OAuth2 client credentials,
 * paginação, {@code updated_since}) sobre as rotas configuradas; modo {@code file}: exportações
 * JSON/CSV. A mensagem bruta é sempre um objeto JSON (item da API ou linha exportada), achatado em
 * caminhos {@code a.b[0].c} e mapeado por YAML.
 *
 * <ul>
 *   <li>{@code regulation_request} → {@code POST /regulation/requests} (upsert por id da
 *       solicitação no e-SUS Regulação);
 *   <li>{@code regulation_status} (eventos) → {@code POST
 *       /regulation/requests/by-source/ESUS_REGULACAO/{id}/status}.
 * </ul>
 */
@ApplicationScoped
public class EsusConnector extends AbstractConnector {

  public static final String SOURCE_SYSTEM = "ESUS_REGULACAO";
  public static final String JSON = "application/json";
  public static final String META_MODE = "mode";
  public static final String META_FILE = "file";

  /** Chave temporária do payload com identificadores do cidadão (removida no transform). */
  static final String CITIZEN_IDENTIFIERS = "citizen_identifiers";

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
  private static final Set<String> KINDS =
      Set.of("consultation", "exam", "procedure", "surgery", "admission");
  private static final Set<String> PRIORITIES =
      Set.of("elective", "priority", "urgent", "emergency");

  private final EsusConfig config;
  private final ObjectMapper mapper;
  private MappingVersion requestMapping;
  private MappingVersion statusMapping;
  private ConnectorDescriptor descriptor;

  @Inject
  public EsusConnector(EsusConfig config, ObjectMapper mapper) {
    this.config = config;
    this.mapper = mapper;
  }

  @PostConstruct
  void init() {
    this.requestMapping = MappingLoader.fromClasspath(config.mapping().request());
    this.statusMapping = MappingLoader.fromClasspath(config.mapping().status());
    boolean api = "api".equalsIgnoreCase(config.mode());
    this.descriptor =
        ConnectorDescriptor.builder()
            .connectorId("connector-esus-regulacao")
            .connectorVersion("0.1.0")
            .sourceSystem(SOURCE_SYSTEM)
            .supportedSourceVersions(List.of(config.sourceVersion()))
            .supportedProtocols(
                List.of("https-json (oauth2 client credentials)", "file-json", "file-csv"))
            .supportedEntities(
                List.of(CanonicalBatch.REGULATION_REQUEST, CanonicalBatch.REGULATION_STATUS))
            .authenticationMethod(
                api
                    ? ConnectorDescriptor.AuthenticationMethod.OIDC_CLIENT_CREDENTIALS
                    : ConnectorDescriptor.AuthenticationMethod.FILE_SYSTEM)
            .requiredNetworkAccess(
                List.of(
                    "core-municipal:8080", "api e-SUS Regulação (" + config.api().baseUrl() + ")"))
            .dataClassification(ConnectorDescriptor.DataClassification.HIGHLY_RESTRICTED)
            .pollingOrEventMode(
                api
                    ? ConnectorDescriptor.IngestionMode.POLLING
                    : ConnectorDescriptor.IngestionMode.FILE_DROP)
            .rateLimitPolicy(new ConnectorDescriptor.RateLimitPolicy(120, 1))
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

  @Override
  public HealthStatus healthCheck() {
    if ("api".equalsIgnoreCase(config.mode())) {
      return new HealthStatus(
          HealthStatus.State.HEALTHY, Map.of("mode", "api", "base_url", config.api().baseUrl()));
    }
    Path in = Path.of(config.file().inputDir());
    return Files.isDirectory(in)
        ? new HealthStatus(
            HealthStatus.State.HEALTHY, Map.of("mode", "file", "input_dir", in.toString()))
        : HealthStatus.degraded("diretório de entrada inexistente: " + in);
  }

  @Override
  public CanonicalBatch transform(RawMessage raw) {
    JsonNode item;
    try {
      item = mapper.readTree(raw.content());
    } catch (IOException e) {
      throw new UncheckedIOException("mensagem e-SUS Regulação inválida (JSON esperado)", e);
    }
    Map<String, String> flat = JsonFlattener.flatten(item);
    MappingVersion mapping =
        switch (raw.entityType()) {
          case CanonicalBatch.REGULATION_REQUEST -> requestMapping;
          case CanonicalBatch.REGULATION_STATUS -> statusMapping;
          default ->
              throw ConnectorException.permanent(
                  "transform", "entity_type não suportado: " + raw.entityType(), null);
        };
    Map<String, Object> payload = MappingEngine.apply(mapping, flat);
    Object identifiers = payload.remove(CITIZEN_IDENTIFIERS);
    if (CanonicalBatch.REGULATION_REQUEST.equals(raw.entityType())) {
      payload.put("citizen_ref", citizenRef(identifiers));
    }
    return new CanonicalBatch(
        raw.entityType(),
        mapping.version(),
        List.of(new CanonicalRecord(raw.sourceRecordId(), raw.sourceRecordVersion(), payload)),
        Map.of("source_record_id", raw.sourceRecordId()));
  }

  /** CNS → CPF → id municipal já conhecido → id local do e-SUS Regulação. */
  static Map<String, Object> citizenRef(Object identifiers) {
    Map<String, Object> ref = new LinkedHashMap<>();
    if (!(identifiers instanceof Map<?, ?> ids)) return ref;
    String cns = digits(ids.get("cns"));
    String cpf = digits(ids.get("cpf"));
    Object municipal = ids.get("municipal_citizen_id");
    Object local = ids.get("local");
    if (cns.length() == 15) {
      ref.put("identifier_system", "CNS");
      ref.put("identifier_value", cns);
    } else if (cpf.length() == 11) {
      ref.put("identifier_system", "CPF");
      ref.put("identifier_value", cpf);
    } else if (municipal != null && !municipal.toString().isBlank()) {
      ref.put("municipal_citizen_id", municipal.toString());
    } else if (local != null && !local.toString().isBlank()) {
      ref.put("identifier_system", SOURCE_SYSTEM);
      ref.put("identifier_value", local.toString());
    }
    return ref;
  }

  private static String digits(Object v) {
    return v == null ? "" : v.toString().replaceAll("\\D", "");
  }

  @Override
  @SuppressWarnings("unchecked")
  public ValidationReport validate(CanonicalBatch batch) {
    ValidationReport.Builder b = ValidationReport.builder();
    for (CanonicalRecord r : batch.records()) {
      Map<String, Object> p = r.payload();
      String id = r.sourceRecordId();
      Map<String, Object> source = (Map<String, Object>) p.get("source");
      b.required(
          id, "source.source_record_id", source == null ? null : source.get("source_record_id"));
      b.required(id, "status", p.get("status"));
      checkEnum(b, id, "status", p.get("status"), STATUSES);
      checkEnum(b, id, "priority", p.get("priority"), PRIORITIES);
      if (CanonicalBatch.REGULATION_REQUEST.equals(batch.entityType())) {
        b.required(id, "kind", p.get("kind"));
        checkEnum(b, id, "kind", p.get("kind"), KINDS);
        b.required(id, "requested_at", p.get("requested_at"));
        b.required(id, "requested_service_code", p.get("requested_service_code"));
        Map<String, Object> ref = (Map<String, Object>) p.get("citizen_ref");
        if (ref == null || ref.isEmpty()) {
          b.error(id, "citizen_ref", "required", "solicitação sem identificador do cidadão");
        } else if ("CNS".equals(ref.get("identifier_system"))
            && !String.valueOf(ref.get("identifier_value")).matches("\\d{15}")) {
          b.error(id, "citizen_ref", "format", "CNS deve ter 15 dígitos");
        }
      } else {
        Map<String, Object> target = (Map<String, Object>) p.get("target_ref");
        b.required(
            id,
            "target_ref.source_record_id",
            target == null ? null : target.get("source_record_id"));
        b.required(id, "occurred_at", p.get("occurred_at"));
      }
      for (String f : List.of("requesting_cnes", "provider_cnes")) {
        if (p.get(f) != null && !p.get(f).toString().matches("\\d{7}")) {
          b.error(id, f, "format", f + " deve ter 7 dígitos");
        }
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
}
