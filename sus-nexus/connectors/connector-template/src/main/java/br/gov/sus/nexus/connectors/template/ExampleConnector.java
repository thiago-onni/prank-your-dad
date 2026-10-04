package br.gov.sus.nexus.connectors.template;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingEngine;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingLoader;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingSet;
import br.gov.sus.nexus.connectors.sdk.parse.DelimitedParser;
import br.gov.sus.nexus.connectors.sdk.runtime.AbstractConnector;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * TEMPLATE — copie este módulo para criar um conector novo.
 *
 * <p>Passos: (1) preencha o {@link ConnectorDescriptor} (todos os campos são obrigatórios); (2)
 * escreva o mapeamento YAML em {@code mappings/}; (3) implemente {@link #transform(RawMessage)} e
 * {@link #validate(CanonicalBatch)}; (4) crie a rota de fonte em {@link ExampleRoutes} entregando
 * {@link RawMessage} em {@code ConnectorRuntime.INGEST}; (5) sobrescreva {@link #healthCheck()} e
 * {@code sourceCounter()} para a fonte real; (6) adapte README, Dockerfile e
 * application.properties.
 */
@ApplicationScoped
public class ExampleConnector extends AbstractConnector {

  private final ConnectorDescriptor descriptor =
      ConnectorDescriptor.builder()
          .connectorId("connector-example")
          .connectorVersion("0.1.0")
          .sourceSystem("EXEMPLO")
          .supportedSourceVersions(List.of("1.0"))
          .supportedProtocols(List.of("file"))
          .supportedEntities(List.of(CanonicalBatch.CITIZEN))
          .authenticationMethod(ConnectorDescriptor.AuthenticationMethod.NONE)
          .requiredNetworkAccess(List.of("core-municipal:8080"))
          .dataClassification(ConnectorDescriptor.DataClassification.HIGHLY_RESTRICTED)
          .pollingOrEventMode(ConnectorDescriptor.IngestionMode.FILE_DROP)
          .retryPolicy(
              new ConnectorDescriptor.RetryPolicySpec(
                  5, Duration.ofSeconds(2), 2.0, Duration.ofMinutes(5)))
          .rateLimitPolicy(new ConnectorDescriptor.RateLimitPolicy(600, 2))
          .fieldMappingVersion("1.0.0")
          .testSuiteVersion("1.0.0")
          .owner("equipe-integracao@sus-nexus")
          .supportSla(
              new ConnectorDescriptor.SupportSla("bronze", Duration.ofHours(8), Duration.ofDays(3)))
          .build();

  private final MappingSet mappings =
      MappingLoader.setFromClasspath("example-citizen", "mappings/example-citizen-1.0.0.yaml");

  @Override
  public ConnectorDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public HealthStatus healthCheck() {
    return HealthStatus.healthy();
  }

  @Override
  public CanonicalBatch transform(RawMessage raw) {
    List<Map<String, String>> rows =
        DelimitedParser.semicolonWithHeader().parse(raw.content(), StandardCharsets.UTF_8);
    List<CanonicalRecord> records =
        rows.stream()
            .map(
                row ->
                    new CanonicalRecord(
                        row.get("id"),
                        raw.sourceRecordVersion(),
                        MappingEngine.apply(
                            mappings.require(descriptor.fieldMappingVersion()), row)))
            .toList();
    return new CanonicalBatch(
        CanonicalBatch.CITIZEN, descriptor.fieldMappingVersion(), records, Map.of());
  }

  @Override
  @SuppressWarnings("unchecked")
  public ValidationReport validate(CanonicalBatch batch) {
    ValidationReport.Builder b = ValidationReport.builder();
    for (CanonicalRecord r : batch.records()) {
      Map<String, Object> d = (Map<String, Object>) r.payload().get("demographics");
      b.required(
          r.sourceRecordId(), "demographics.legal_name", d == null ? null : d.get("legal_name"));
      b.required(
          r.sourceRecordId(), "demographics.birthdate", d == null ? null : d.get("birthdate"));
    }
    return b.build();
  }
}
