package br.gov.sus.nexus.connectors.sisreg;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.runtime.ConnectorRuntime;
import br.gov.sus.nexus.connectors.sdk.runtime.PipelineHeaders;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.jboss.logging.Logger;

/**
 * Rota de fonte: diretório de entrada com exportações {@code .csv}/{@code .xlsx}. O arquivo inteiro
 * é lido, seu SHA-256 consultado no {@link ProcessedFileRegistry} (marca d'água por arquivo) e cada
 * linha vira uma {@link RawMessage} JSON ({@code cabeçalho original → valor}) entregue ao pipeline.
 * Arquivos sem layout correspondente vão para {@code .unknown/}.
 */
@ApplicationScoped
public class SisregRoutes extends RouteBuilder {

  public static final String HEADER_SKIP = "SisregSkip";

  private static final Logger LOG = Logger.getLogger(SisregRoutes.class);

  private final SisregConfig config;
  private final SisregConnector connector;
  private final ObjectMapper mapper;
  private ProcessedFileRegistry registry;
  private SpreadsheetReader reader;

  @Inject
  public SisregRoutes(SisregConfig config, SisregConnector connector, ObjectMapper mapper) {
    this.config = config;
    this.connector = connector;
    this.mapper = mapper;
  }

  public ProcessedFileRegistry registry() {
    return registry;
  }

  @Override
  public void configure() {
    registry = new ProcessedFileRegistry(Path.of(config.file().processedRegistry()), mapper);
    reader =
        new SpreadsheetReader(
            Charset.forName(config.file().charset()),
            config.file().delimiter().charAt(0),
            config.file().sheetIndex());

    from("file:"
            + config.file().inputDir()
            + "?delay="
            + config.file().pollDelayMs()
            + "&readLock=changed&readLockCheckInterval=500&readLockMinAge=500"
            + "&move=.done/${date:now:yyyyMMdd}/${file:name}&moveFailed=.error/${file:name}"
            + "&includeExt=csv,CSV,xlsx,XLSX")
        .routeId("sisreg-file-source")
        .log(LoggingLevel.INFO, "exportação SISREG recebida: ${file:name}")
        .process(this::splitFile)
        .filter(header(HEADER_SKIP).isNull())
        .split(body())
        .to(ConnectorRuntime.INGEST)
        .end()
        .process(this::markProcessed)
        .end();
  }

  private void splitFile(Exchange exchange) {
    String fileName = exchange.getIn().getHeader(Exchange.FILE_NAME_ONLY, String.class);
    byte[] content = exchange.getIn().getBody(byte[].class);
    String sha256 = Hashes.sha256Hex(content);
    exchange.setProperty("sisregSha256", sha256);
    exchange.setProperty("sisregFileName", fileName);
    if (registry.isProcessed(sha256)) {
      LOG.infof(
          "arquivo %s já processado (sha256 %s); ignorado", fileName, sha256.substring(0, 12));
      exchange.getIn().setHeader(HEADER_SKIP, "duplicate");
      exchange.getIn().setBody(List.of());
      return;
    }
    Optional<SisregLayout.Kind> kind = connector.layout().forFile(fileName);
    if (kind.isEmpty()) {
      LOG.warnf("arquivo %s não corresponde a nenhum kind do layout; ignorado", fileName);
      exchange.getIn().setHeader(HEADER_SKIP, "unknown-layout");
      exchange.getIn().setBody(List.of());
      return;
    }
    List<Map<String, String>> rows = reader.read(fileName, content);
    List<RawMessage> messages = new ArrayList<>();
    int line = 0;
    for (Map<String, String> row : rows) {
      line++;
      Map<String, String> canonical = kind.get().canonicalize(row);
      String id = idOf(kind.get(), canonical, sha256, line);
      byte[] json = toJson(row);
      String version = versionOf(kind.get(), canonical, json);
      messages.add(
          new RawMessage(
              id,
              version,
              kind.get().entity(),
              SisregConnector.JSON,
              json,
              Map.of(
                  SisregConnector.META_FILE,
                  fileName,
                  SisregConnector.META_KIND,
                  kind.get().name(),
                  SisregConnector.META_FILE_SHA256,
                  sha256,
                  "line",
                  String.valueOf(line)),
              Instant.now()));
    }
    exchange.setProperty("sisregRows", messages.size());
    exchange.getIn().setHeader(PipelineHeaders.CORRELATION_ID, Ids.correlation());
    exchange.getIn().setBody(messages);
  }

  private void markProcessed(Exchange exchange) {
    if (exchange.getIn().getHeader(HEADER_SKIP) != null) return;
    registry.markProcessed(
        exchange.getProperty("sisregSha256", String.class),
        exchange.getProperty("sisregFileName", String.class),
        exchange.getProperty("sisregRows", 0, Integer.class));
  }

  /** Id do registro: coluna declarada no layout; oferta usa executante+procedimento+competência. */
  static String idOf(SisregLayout.Kind kind, Map<String, String> canonical, String sha, int line) {
    if (kind.idColumn() != null) {
      String v = canonical.getOrDefault(kind.idColumn(), "");
      if (!v.isBlank()) return v.trim();
    }
    if ("provider_capacity".equals(kind.entity())) {
      String composed =
          String.join(
              ":",
              SisregRules.digits(canonical.getOrDefault("cnes_executante", "")),
              SisregRules.digits(canonical.getOrDefault("codigo_procedimento", "")),
              canonical.getOrDefault("competencia", "").replaceAll("\\D", ""));
      if (!composed.equals("::")) return composed;
    }
    return sha.substring(0, 16) + ":" + line;
  }

  private static String versionOf(
      SisregLayout.Kind kind, Map<String, String> canonical, byte[] json) {
    if (kind.versionColumn() != null) {
      String v = canonical.getOrDefault(kind.versionColumn(), "");
      if (!v.isBlank()) return v.trim();
    }
    return Hashes.sha256Hex(json).substring(0, 16);
  }

  private byte[] toJson(Map<String, String> row) {
    try {
      return mapper.writeValueAsBytes(row);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }
}
