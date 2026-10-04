package br.gov.sus.nexus.connectors.sdk.runtime;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingEngine;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingLoader;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingSet;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conector de teste: linha "id;nome;dt_nasc;sexo;cpf" → CitizenRegistration. */
@ApplicationScoped
public class TestConnector extends AbstractConnector {

  private final ConnectorDescriptor descriptor =
      ConnectorDescriptor.builder()
          .connectorId("connector-test")
          .connectorVersion("0.1.0")
          .sourceSystem("TESTE")
          .supportedSourceVersions(List.of("1"))
          .supportedProtocols(List.of("direct"))
          .supportedEntities(List.of(CanonicalBatch.CITIZEN))
          .authenticationMethod(ConnectorDescriptor.AuthenticationMethod.NONE)
          .requiredNetworkAccess(List.of("core-municipal:8080"))
          .dataClassification(ConnectorDescriptor.DataClassification.HIGHLY_RESTRICTED)
          .pollingOrEventMode(ConnectorDescriptor.IngestionMode.EVENT)
          .fieldMappingVersion("1.0.0")
          .testSuiteVersion("1.0.0")
          .owner("equipe-integracao")
          .supportSla(
              new ConnectorDescriptor.SupportSla("bronze", Duration.ofHours(8), Duration.ofDays(3)))
          .build();

  private final MappingSet mappings =
      MappingLoader.setFromClasspath("test-citizen", "mappings/test-citizen-1.0.0.yaml");

  @Override
  public ConnectorDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public CanonicalBatch transform(RawMessage raw) {
    String[] cols = raw.contentAsString().split(";", -1);
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("id", cols[0]);
    source.put("nome", cols[1]);
    source.put("dt_nasc", cols[2]);
    source.put("sexo", cols.length > 3 ? cols[3] : null);
    source.put("cpf", cols.length > 4 ? cols[4] : null);
    Map<String, Object> payload =
        MappingEngine.apply(mappings.require(descriptor.fieldMappingVersion()), source);
    return new CanonicalBatch(
        CanonicalBatch.CITIZEN,
        descriptor.fieldMappingVersion(),
        List.of(new CanonicalRecord(raw.sourceRecordId(), raw.sourceRecordVersion(), payload)),
        Map.of());
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
