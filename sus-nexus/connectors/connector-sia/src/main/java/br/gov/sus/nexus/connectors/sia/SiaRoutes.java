package br.gov.sus.nexus.connectors.sia;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.Period;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ReconciliationReport;
import br.gov.sus.nexus.connectors.sdk.core.CoreIntegrationMirror;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.jboss.logging.Logger;

/**
 * Rotas do conector SIA/SIH:
 *
 * <ul>
 *   <li>duas pastas monitoradas ({@code sia.file.production-dir} → kinds {@code group: producao};
 *       {@code sia.file.returns-dir} → kinds {@code group: retorno}), normalmente um SFTP montado.
 *       O arquivo é lido inteiro, o SHA-256 consultado no {@link ProcessedFileRegistry} (marca
 *       d'água por arquivo) e cada linha de dados vira uma {@link RawMessage} JSON ({@code
 *       layout_kind}, arquivo, SHA-256 do arquivo, nº da linha e campos de origem) entregue ao
 *       pipeline do SDK. Arquivo sem layout correspondente ou já processado é apenas registrado em
 *       log (sem linhas) e movido para {@code .done/};
 *   <li>heartbeat no core (contagens das últimas 24 h) e reconciliação linhas lidas × publicadas.
 * </ul>
 */
@ApplicationScoped
public class SiaRoutes extends RouteBuilder {

  public static final String HEADER_SKIP = "SiaSkip";

  private static final Logger LOG = Logger.getLogger(SiaRoutes.class);

  private final SiaConfig config;
  private final SiaConnector connector;
  private final ObjectMapper mapper;
  private final IntegrationMessageLedger ledger;
  private final CoreIntegrationMirror core;

  @Inject
  public SiaRoutes(
      SiaConfig config,
      SiaConnector connector,
      ObjectMapper mapper,
      IntegrationMessageLedger ledger,
      CoreIntegrationMirror core) {
    this.config = config;
    this.connector = connector;
    this.mapper = mapper;
    this.ledger = ledger;
    this.core = core;
  }

  @Override
  public void configure() {
    fileRoute("sia-production-files", config.file().productionDir(), SiaLayout.GROUP_PRODUCTION);
    fileRoute("sia-return-files", config.file().returnsDir(), SiaLayout.GROUP_RETURN);
    if (config.heartbeat().enabled()) {
      from("timer:sia-heartbeat?delay=5000&period=" + config.heartbeat().periodMs())
          .routeId("sia-heartbeat")
          .process(e -> heartbeat());
    }
    if (config.reconciliation().enabled()) {
      from("timer:sia-reconciliation?delay=60000&period=" + config.reconciliation().periodMs())
          .routeId("sia-reconciliation")
          .process(e -> reconcile());
    }
  }

  private void fileRoute(String routeId, String dir, String group) {
    from("file:"
            + dir
            + "?delay="
            + config.file().pollDelayMs()
            + "&readLock=changed&readLockCheckInterval=500&readLockMinAge=500"
            + "&move=.done/${date:now:yyyyMMdd}/${file:name}&moveFailed=.error/${file:name}"
            + "&exclude=.*\\.(part|tmp)$")
        .routeId(routeId)
        .log(LoggingLevel.INFO, "arquivo " + group + " recebido: ${file:name}")
        .setProperty("siaGroup", constant(group))
        .process(this::splitFile)
        .filter(header(HEADER_SKIP).isNull())
        .split(body())
        .to(ConnectorRuntime.INGEST)
        .end()
        .process(this::markProcessed)
        .end();
  }

  void splitFile(Exchange exchange) {
    String group = exchange.getProperty("siaGroup", String.class);
    String fileName = exchange.getIn().getHeader(Exchange.FILE_NAME_ONLY, String.class);
    byte[] content = exchange.getIn().getBody(byte[].class);
    String sha256 = Hashes.sha256Hex(content);
    exchange.setProperty("siaSha256", sha256);
    exchange.setProperty("siaFileName", fileName);
    if (connector.registry().isProcessed(sha256)) {
      LOG.infof(
          "arquivo %s já processado (sha256 %s); ignorado", fileName, sha256.substring(0, 12));
      skip(exchange, "duplicate");
      return;
    }
    Optional<SiaLayout.Kind> kind = connector.layout().forFile(group, fileName);
    if (kind.isEmpty()) {
      LOG.warnf(
          "arquivo %s (%s) não corresponde a nenhum kind do layout; ignorado", fileName, group);
      skip(exchange, "unknown-layout");
      return;
    }
    if (kind.get().pendingConfirmation()) {
      LOG.warnf(
          "layout %s ainda A CONFIRMAR (homologação pendente): processando %s",
          kind.get().name(), fileName);
    }
    List<RawMessage> messages =
        toMessages(kind.get(), fileName, sha256, content, Charset.forName(config.file().charset()));
    exchange.setProperty("siaKind", kind.get().name());
    exchange.setProperty("siaEntity", kind.get().entity());
    exchange.setProperty("siaRows", messages.size());
    exchange.getIn().setHeader(PipelineHeaders.CORRELATION_ID, Ids.correlation());
    exchange.getIn().setBody(messages);
  }

  /** Uma {@link RawMessage} por linha de dados (idempotência: arquivo + linha). */
  List<RawMessage> toMessages(
      SiaLayout.Kind kind, String fileName, String sha256, byte[] content, Charset charset) {
    List<Map<String, String>> rows = kind.read(content, charset);
    List<RawMessage> messages = new ArrayList<>();
    int line = 0;
    for (Map<String, String> row : rows) {
      line++;
      Map<String, String> canonical = kind.canonicalize(row);
      if (!kind.accepts(canonical)) continue; // row_filter do layout (numeração preservada)
      Map<String, Object> wrapper = new LinkedHashMap<>();
      wrapper.put("layout_kind", kind.name());
      wrapper.put("layout_version", connector.layout().version());
      wrapper.put("file", fileName);
      wrapper.put("file_sha256", sha256);
      wrapper.put("line", line);
      wrapper.put("fields", row);
      byte[] json = toJson(wrapper);
      messages.add(
          new RawMessage(
              idOf(kind, canonical, sha256, line),
              versionOf(kind, canonical, row),
              kind.entity(),
              SiaConnector.JSON,
              json,
              Map.of(
                  SiaConnector.META_KIND,
                  kind.name(),
                  SiaConnector.META_FILE,
                  fileName,
                  SiaConnector.META_FILE_SHA256,
                  sha256,
                  SiaConnector.META_LINE,
                  String.valueOf(line)),
              Instant.now()));
    }
    return messages;
  }

  private static void skip(Exchange exchange, String reason) {
    exchange.getIn().setHeader(HEADER_SKIP, reason);
    exchange.getIn().setBody(List.of());
  }

  private void markProcessed(Exchange exchange) {
    if (exchange.getIn().getHeader(HEADER_SKIP) != null) return;
    connector
        .registry()
        .markProcessed(
            exchange.getProperty("siaSha256", String.class),
            exchange.getProperty("siaFileName", String.class),
            exchange.getProperty("siaKind", String.class),
            exchange.getProperty("siaEntity", String.class),
            exchange.getProperty("siaRows", 0, Integer.class));
  }

  /** Id do registro: coluna declarada no layout; senão {@code <sha256[0:16]>:<linha>}. */
  static String idOf(SiaLayout.Kind kind, Map<String, String> canonical, String sha, int line) {
    if (kind.idColumn() != null) {
      String v = canonical.getOrDefault(kind.idColumn(), "");
      if (!v.isBlank()) return v.trim();
    }
    return sha.substring(0, 16) + ":" + line;
  }

  private String versionOf(
      SiaLayout.Kind kind, Map<String, String> canonical, Map<String, String> row) {
    if (kind.versionColumn() != null) {
      String v = canonical.getOrDefault(kind.versionColumn(), "");
      if (!v.isBlank()) return v.trim();
    }
    return Hashes.sha256Hex(toJson(row)).substring(0, 16);
  }

  private byte[] toJson(Object value) {
    try {
      return mapper.writeValueAsBytes(value);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Heartbeat no core com contagens das últimas 24 h por entidade. */
  public void heartbeat() {
    Period last24h = Period.last(Duration.ofHours(24));
    Map<String, Object> metrics = new LinkedHashMap<>();
    for (String entity :
        List.of(CanonicalBatch.PRODUCTION_RECORD, CanonicalBatch.PRODUCTION_OUTCOME)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("rows_read_24h", connector.registry().rows(entity, last24h));
      m.put("published_24h", ledger.countPublished(entity, last24h));
      m.put(
          "dead_lettered_24h",
          ledger.count(entity, IntegrationMessageStatus.DEAD_LETTERED, last24h));
      metrics.put(entity, m);
    }
    metrics.put("processed_files", connector.registry().size());
    core.heartbeat(connector.descriptor(), connector.healthCheck(), metrics);
  }

  /** Reconciliação linhas lidas × publicadas (janela {@code sia.reconciliation.window}). */
  public ReconciliationReport reconcile() {
    ReconciliationReport report =
        connector.reconcile(Period.last(config.reconciliation().window()));
    core.reconciliation(report);
    return report;
  }
}
