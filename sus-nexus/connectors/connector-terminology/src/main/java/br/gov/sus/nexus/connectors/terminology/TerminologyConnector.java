package br.gov.sus.nexus.connectors.terminology;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.parse.LayoutRegistry;
import br.gov.sus.nexus.connectors.sdk.parse.TableLayout;
import br.gov.sus.nexus.connectors.sdk.reconcile.SourceCounter;
import br.gov.sus.nexus.connectors.sdk.runtime.AbstractConnector;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conector de terminologia: SIGTAP (largura fixa), CID-10, CBO e CIAP-2 (CSV) por arquivo. Cada
 * arquivo vira uma mensagem; cada linha vira um {@code CodeUpsert} publicado em lotes no core.
 */
@ApplicationScoped
public class TerminologyConnector extends AbstractConnector {

  public static final String META_TABLE = "table";
  public static final String META_FILE = "file";

  private final TerminologyConfig config;
  private LayoutRegistry layouts;
  private ConnectorDescriptor descriptor;

  @Inject
  public TerminologyConnector(TerminologyConfig config) {
    this.config = config;
  }

  @PostConstruct
  void init() {
    this.layouts = LayoutRegistry.load(config.layouts());
    this.descriptor =
        ConnectorDescriptor.builder()
            .connectorId("connector-terminology")
            .connectorVersion("0.1.0")
            .sourceSystem("SIGTAP")
            .supportedSourceVersions(
                List.of("SIGTAP competência AAAAMM", "CID-10 2008+", "CBO 2002", "CIAP-2"))
            .supportedProtocols(List.of("file"))
            .supportedEntities(List.of(CanonicalBatch.CODE))
            .authenticationMethod(ConnectorDescriptor.AuthenticationMethod.FILE_SYSTEM)
            .requiredNetworkAccess(
                List.of("core-municipal:8080", "sigtap.datasus.gov.br (download manual/FTP)"))
            .dataClassification(ConnectorDescriptor.DataClassification.PUBLIC)
            .pollingOrEventMode(ConnectorDescriptor.IngestionMode.FILE_DROP)
            .rateLimitPolicy(new ConnectorDescriptor.RateLimitPolicy(60, 1))
            .fieldMappingVersion(layouts.version())
            .testSuiteVersion("1.0.0")
            .owner("equipe-integracao@sus-nexus")
            .supportSla(
                new ConnectorDescriptor.SupportSla(
                    "silver", Duration.ofHours(4), Duration.ofDays(2)))
            .build();
  }

  @Override
  public ConnectorDescriptor descriptor() {
    return descriptor;
  }

  public LayoutRegistry layouts() {
    return layouts;
  }

  @Override
  public HealthStatus healthCheck() {
    Path in = Path.of(config.inputDir());
    if (!Files.isDirectory(in)) {
      return HealthStatus.degraded("diretório de entrada inexistente: " + in);
    }
    return new HealthStatus(
        HealthStatus.State.HEALTHY,
        Map.of("input_dir", in.toString(), "layouts", layouts.version()));
  }

  @Override
  public CanonicalBatch transform(RawMessage raw) {
    String tableName = raw.metadata().get(META_TABLE);
    TableLayout layout =
        layouts
            .byName(tableName == null ? "" : tableName)
            .or(() -> layouts.forFile(raw.sourceRecordId()))
            .orElseThrow(
                () ->
                    ConnectorException.permanent(
                        "transform", "nenhum layout para " + raw.sourceRecordId(), null));
    List<Map<String, String>> rows = layout.parse(raw.content());
    List<CanonicalRecord> records = new ArrayList<>(rows.size());
    String competence = null;
    for (Map<String, String> row : rows) {
      String code = layout.value(row, "code");
      String display = layout.value(row, "display");
      String rowCompetence = layout.value(row, "competence");
      if (competence == null && rowCompetence != null && !rowCompetence.isBlank())
        competence = rowCompetence;
      Map<String, Object> attributes = new LinkedHashMap<>();
      for (String col : layout.attributeColumns()) {
        String v = row.get(col);
        if (v != null && !v.isBlank()) attributes.put(col.toLowerCase(), v);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("code", code);
      payload.put("display", display);
      if (rowCompetence != null && !rowCompetence.isBlank())
        payload.put("competence_from", rowCompetence);
      if (!attributes.isEmpty()) payload.put("attributes", attributes);
      records.add(
          new CanonicalRecord(layout.system() + ":" + code, raw.sourceRecordVersion(), payload));
    }
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("system", layout.system());
    attrs.put("source_record_id", raw.sourceRecordId());
    if (raw.sourceRecordVersion() != null) {
      attrs.put("source_record_version", raw.sourceRecordVersion());
    }
    attrs.put("version", layouts.version());
    if (competence == null) competence = raw.metadata().get("competence");
    if (competence != null) attrs.put("competence", competence);
    return new CanonicalBatch(CanonicalBatch.CODE, layouts.version(), records, attrs);
  }

  @Override
  public ValidationReport validate(CanonicalBatch batch) {
    ValidationReport.Builder b = ValidationReport.builder();
    if (batch.isEmpty())
      b.error(batch.attributes().get("source_record_id"), "records", "empty", "arquivo sem linhas");
    Set<String> seen = new HashSet<>();
    for (CanonicalRecord r : batch.records()) {
      b.required(r.sourceRecordId(), "code", r.payload().get("code"));
      b.required(r.sourceRecordId(), "display", r.payload().get("display"));
      if (!seen.add(String.valueOf(r.payload().get("code")))) {
        b.warning(r.sourceRecordId(), "code", "duplicate", "código repetido no arquivo");
      }
    }
    return b.build();
  }

  /** Fonte = arquivos: a contagem na fonte é a quantidade de arquivos processados no período. */
  @Override
  protected SourceCounter sourceCounter() {
    return (entity, period) -> ledger.countPublished(entity, period);
  }
}
