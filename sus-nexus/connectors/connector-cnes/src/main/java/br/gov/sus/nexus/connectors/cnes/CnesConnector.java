package br.gov.sus.nexus.connectors.cnes;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalRecord;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingEngine;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingLoader;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingVersion;
import br.gov.sus.nexus.connectors.sdk.parse.DelimitedParser;
import br.gov.sus.nexus.connectors.sdk.runtime.AbstractConnector;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Conector CNES por arquivo oficial de competência (tbEstabelecimento CSV/DBF da
 * BASE_DE_DADOS_CNES_AAAAMM). Cada arquivo vira uma mensagem; cada estabelecimento vira um {@code
 * HealthUnitUpsert}. Plano A (web services DATASUS SOAP) fica como pendência documentada no README.
 */
@ApplicationScoped
public class CnesConnector extends AbstractConnector {

  /**
   * Colunas conhecidas; em DBF os nomes vêm truncados a 10 caracteres e são resolvidos por prefixo.
   */
  static final List<String> KNOWN_COLUMNS =
      List.of(
          "CO_UNIDADE",
          "CO_CNES",
          "NU_CNPJ_MANTENEDORA",
          "NO_RAZAO_SOCIAL",
          "NO_FANTASIA",
          "NO_LOGRADOURO",
          "NU_ENDERECO",
          "NO_COMPLEMENTO",
          "NO_BAIRRO",
          "CO_CEP",
          "NU_TELEFONE",
          "TP_UNIDADE",
          "CO_TIPO_UNIDADE",
          "CO_MUNICIPIO_GESTOR");

  private final CnesConfig config;
  private MappingVersion mapping;
  private ConnectorDescriptor descriptor;

  @Inject
  public CnesConnector(CnesConfig config) {
    this.config = config;
  }

  @PostConstruct
  void init() {
    this.mapping = MappingLoader.fromClasspath(config.mapping());
    this.descriptor =
        ConnectorDescriptor.builder()
            .connectorId("connector-cnes")
            .connectorVersion("0.1.0")
            .sourceSystem("CNES")
            .supportedSourceVersions(List.of("BASE_DE_DADOS_CNES competência AAAAMM (CSV/DBF)"))
            .supportedProtocols(List.of("file"))
            .supportedEntities(List.of(CanonicalBatch.HEALTH_UNIT))
            .authenticationMethod(ConnectorDescriptor.AuthenticationMethod.FILE_SYSTEM)
            .requiredNetworkAccess(List.of("core-municipal:8080", "cnes.datasus.gov.br (download)"))
            .dataClassification(ConnectorDescriptor.DataClassification.PUBLIC)
            .pollingOrEventMode(ConnectorDescriptor.IngestionMode.FILE_DROP)
            .rateLimitPolicy(new ConnectorDescriptor.RateLimitPolicy(60, 1))
            .fieldMappingVersion(mapping.version())
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

  @Override
  public HealthStatus healthCheck() {
    Path in = Path.of(config.inputDir());
    return Files.isDirectory(in)
        ? new HealthStatus(HealthStatus.State.HEALTHY, Map.of("input_dir", in.toString()))
        : HealthStatus.degraded("diretório de entrada inexistente: " + in);
  }

  @Override
  public CanonicalBatch transform(RawMessage raw) {
    Charset charset = Charset.forName(config.charset());
    String name = raw.sourceRecordId().toLowerCase(Locale.ROOT);
    List<Map<String, String>> rows =
        name.endsWith(".dbf")
            ? DbfReader.read(raw.content(), charset)
            : new DelimitedParser(config.csvDelimiter().charAt(0), true, List.of())
                .parse(raw.content(), charset);
    List<CanonicalRecord> records = new ArrayList<>();
    for (Map<String, String> row : rows) {
      Map<String, String> upper = new LinkedHashMap<>();
      row.forEach((k, v) -> upper.put(k.toUpperCase(Locale.ROOT), v));
      resolveTruncatedNames(upper);
      if (config.municipioGestor().isPresent()
          && !config
              .municipioGestor()
              .get()
              .equals(upper.getOrDefault("CO_MUNICIPIO_GESTOR", ""))) {
        continue;
      }
      upper.put("ENDERECO", composeAddress(upper));
      if (!upper.containsKey("CO_TIPO_UNIDADE") && upper.containsKey("TP_UNIDADE")) {
        upper.put("CO_TIPO_UNIDADE", upper.get("TP_UNIDADE"));
      }
      Map<String, Object> payload = MappingEngine.apply(mapping, upper);
      records.add(
          new CanonicalRecord("CNES:" + upper.get("CO_CNES"), raw.sourceRecordVersion(), payload));
    }
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("source_record_id", raw.sourceRecordId());
    if (raw.sourceRecordVersion() != null) {
      attrs.put("source_record_version", raw.sourceRecordVersion());
    }
    String competence = raw.metadata().get("competence");
    if (competence != null) attrs.put("competence", competence);
    return new CanonicalBatch(CanonicalBatch.HEALTH_UNIT, mapping.version(), records, attrs);
  }

  /** DBF trunca nomes a 10 caracteres (NO_FANTASI, CO_MUNICIP...): copia para o nome completo. */
  static void resolveTruncatedNames(Map<String, String> row) {
    for (String full : KNOWN_COLUMNS) {
      if (row.containsKey(full) || full.length() <= 10) continue;
      String truncated = full.substring(0, 10);
      if (row.containsKey(truncated)) row.put(full, row.get(truncated));
    }
  }

  static String composeAddress(Map<String, String> row) {
    StringBuilder sb = new StringBuilder();
    append(sb, row.get("NO_LOGRADOURO"), "");
    append(sb, row.get("NU_ENDERECO"), ", ");
    append(sb, row.get("NO_COMPLEMENTO"), " ");
    append(sb, row.get("NO_BAIRRO"), " - ");
    append(sb, row.get("CO_CEP"), " CEP ");
    return sb.toString().trim();
  }

  private static void append(StringBuilder sb, String v, String sep) {
    if (v == null || v.isBlank()) return;
    if (!sb.isEmpty()) sb.append(sep);
    sb.append(v.trim());
  }

  @Override
  public ValidationReport validate(CanonicalBatch batch) {
    ValidationReport.Builder b = ValidationReport.builder();
    if (batch.isEmpty())
      b.error(
          batch.attributes().get("source_record_id"),
          "records",
          "empty",
          "arquivo sem estabelecimentos");
    Set<String> seen = new HashSet<>();
    for (CanonicalRecord r : batch.records()) {
      Object cnes = r.payload().get("cnes");
      b.required(r.sourceRecordId(), "cnes", cnes);
      b.required(r.sourceRecordId(), "name", r.payload().get("name"));
      if (cnes != null && !cnes.toString().matches("\\d{7}")) {
        b.error(r.sourceRecordId(), "cnes", "format", "CNES deve ter 7 dígitos");
      }
      if (cnes != null && !seen.add(cnes.toString())) {
        b.warning(r.sourceRecordId(), "cnes", "duplicate", "CNES repetido no arquivo");
      }
    }
    return b.build();
  }
}
