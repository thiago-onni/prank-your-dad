package br.gov.sus.nexus.connectors.pec;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conector e-SUS APS/PEC. Somente leitura: nunca escreve no PEC. Modos: {@code file} (CSV exportado
 * de cidadãos e agendamentos) e {@code jdbc} (réplica PostgreSQL, consultas em {@code sql/*.sql}).
 * Cada linha vira uma mensagem (ledger e DLQ por registro).
 */
@ApplicationScoped
public class PecConnector extends AbstractConnector {

  public static final String META_MODE = "mode";
  public static final String META_FILE = "file";

  private final PecConfig config;
  private final ObjectMapper mapper;
  private MappingVersion citizenMapping;
  private MappingVersion appointmentMapping;
  private ConnectorDescriptor descriptor;

  @Inject
  public PecConnector(PecConfig config, ObjectMapper mapper) {
    this.config = config;
    this.mapper = mapper;
  }

  @PostConstruct
  void init() {
    this.citizenMapping = MappingLoader.fromClasspath(config.mapping().citizen());
    this.appointmentMapping = MappingLoader.fromClasspath(config.mapping().appointment());
    this.descriptor =
        ConnectorDescriptor.builder()
            .connectorId("connector-pec")
            .connectorVersion("0.1.0")
            .sourceSystem("PEC")
            .supportedSourceVersions(
                List.of("e-SUS APS PEC " + config.sourceVersion() + " (réplica/CSV)"))
            .supportedProtocols(List.of("file", "jdbc-postgresql-readonly"))
            .supportedEntities(List.of(CanonicalBatch.CITIZEN, CanonicalBatch.APPOINTMENT))
            .authenticationMethod(
                "jdbc".equalsIgnoreCase(config.mode())
                    ? ConnectorDescriptor.AuthenticationMethod.DATABASE_CREDENTIALS
                    : ConnectorDescriptor.AuthenticationMethod.FILE_SYSTEM)
            .requiredNetworkAccess(
                List.of("core-municipal:8080", "replica-pec:5432 (somente modo jdbc)"))
            .dataClassification(ConnectorDescriptor.DataClassification.HIGHLY_RESTRICTED)
            .pollingOrEventMode(
                "jdbc".equalsIgnoreCase(config.mode())
                    ? ConnectorDescriptor.IngestionMode.POLLING
                    : ConnectorDescriptor.IngestionMode.FILE_DROP)
            .rateLimitPolicy(new ConnectorDescriptor.RateLimitPolicy(600, 2))
            .fieldMappingVersion(citizenMapping.version())
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
    if ("jdbc".equalsIgnoreCase(config.mode())) {
      return new HealthStatus(HealthStatus.State.HEALTHY, Map.of("mode", "jdbc"));
    }
    Path in = Path.of(config.file().inputDir());
    return Files.isDirectory(in)
        ? new HealthStatus(
            HealthStatus.State.HEALTHY, Map.of("mode", "file", "input_dir", in.toString()))
        : HealthStatus.degraded("diretório de entrada inexistente: " + in);
  }

  @Override
  public CanonicalBatch transform(RawMessage raw) {
    Map<String, String> row =
        PecRows.fromRaw(
            raw,
            mapper,
            Charset.forName(config.file().charset()),
            config.file().delimiter().charAt(0));
    Map<String, Object> payload;
    MappingVersion mapping;
    switch (raw.entityType()) {
      case CanonicalBatch.CITIZEN -> {
        mapping = citizenMapping;
        payload = MappingEngine.apply(mapping, row);
      }
      case CanonicalBatch.APPOINTMENT -> {
        mapping = appointmentMapping;
        payload = MappingEngine.apply(mapping, row);
        payload.put("citizen_ref", citizenRef(row));
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

  /** Referência ao cidadão por prioridade: CPF → CNS → id do PEC. */
  static Map<String, Object> citizenRef(Map<String, String> row) {
    Map<String, Object> ref = new LinkedHashMap<>();
    String cpf = digits(row.get("nu_cpf_cidadao"));
    String cns = digits(row.get("nu_cns_cidadao"));
    if (cpf.length() == 11) {
      ref.put("identifier_system", "CPF");
      ref.put("identifier_value", cpf);
    } else if (cns.length() == 15) {
      ref.put("identifier_system", "CNS");
      ref.put("identifier_value", cns);
    } else {
      ref.put("identifier_system", "PEC");
      ref.put("identifier_value", row.getOrDefault("co_cidadao", ""));
    }
    return ref;
  }

  private static String digits(String v) {
    return v == null ? "" : v.replaceAll("\\D", "");
  }

  @Override
  @SuppressWarnings("unchecked")
  public ValidationReport validate(CanonicalBatch batch) {
    ValidationReport.Builder b = ValidationReport.builder();
    for (CanonicalRecord r : batch.records()) {
      Map<String, Object> p = r.payload();
      Map<String, Object> source = (Map<String, Object>) p.get("source");
      b.required(
          r.sourceRecordId(),
          "source.source_record_id",
          source == null ? null : source.get("source_record_id"));
      if (CanonicalBatch.CITIZEN.equals(batch.entityType())) {
        Map<String, Object> d = (Map<String, Object>) p.get("demographics");
        b.required(
            r.sourceRecordId(), "demographics.legal_name", d == null ? null : d.get("legal_name"));
        b.required(
            r.sourceRecordId(), "demographics.birthdate", d == null ? null : d.get("birthdate"));
        List<Map<String, Object>> ids =
            (List<Map<String, Object>>) p.getOrDefault("identifiers", List.of());
        for (Map<String, Object> id : ids) {
          String system = String.valueOf(id.get("system"));
          String value = String.valueOf(id.get("value"));
          if ("CPF".equals(system) && !value.matches("\\d{11}")) {
            b.error(r.sourceRecordId(), "identifiers.CPF", "format", "CPF deve ter 11 dígitos");
          }
          if ("CNS".equals(system) && !value.matches("\\d{15}")) {
            b.error(r.sourceRecordId(), "identifiers.CNS", "format", "CNS deve ter 15 dígitos");
          }
        }
      } else {
        b.required(r.sourceRecordId(), "status", p.get("status"));
        b.required(r.sourceRecordId(), "kind", p.get("kind"));
        b.required(r.sourceRecordId(), "scheduled_start", p.get("scheduled_start"));
        Map<String, Object> ref = (Map<String, Object>) p.get("citizen_ref");
        b.required(
            r.sourceRecordId(),
            "citizen_ref.identifier_value",
            ref == null ? null : ref.get("identifier_value"));
      }
    }
    return b.build();
  }
}
