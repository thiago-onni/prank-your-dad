package br.gov.sus.nexus.connectors.esusreg;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.parse.DelimitedParser;
import br.gov.sus.nexus.connectors.sdk.runtime.ConnectorRuntime;
import br.gov.sus.nexus.connectors.sdk.runtime.PipelineHeaders;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.jboss.logging.Logger;

/**
 * Rotas de fonte do e-SUS Regulação.
 *
 * <ul>
 *   <li>{@code api}: timer → {@link EsusApiClient#listSince} nas rotas de solicitações e eventos
 *       com {@code updated_since} = marca d'água → uma {@link RawMessage} (JSON do item) por item →
 *       marca d'água avança para o maior {@code atualizado_em} visto quando a varredura termina.
 *   <li>{@code file} (sempre ativo): {@code solicitacoes*.json|csv} e {@code eventos*.json|csv} em
 *       {@code esus.file.input-dir}; JSON pode ser array ou objeto com {@code items}.
 * </ul>
 */
@ApplicationScoped
public class EsusRoutes extends RouteBuilder {

  private static final Logger LOG = Logger.getLogger(EsusRoutes.class);

  private final EsusConfig config;
  private final ObjectMapper mapper;
  private WatermarkStore watermarks;
  private EsusApiClient client;

  @Inject
  public EsusRoutes(EsusConfig config, ObjectMapper mapper) {
    this.config = config;
    this.mapper = mapper;
  }

  public WatermarkStore watermarks() {
    return watermarks;
  }

  @Override
  public void configure() {
    watermarks =
        new WatermarkStore(
            Path.of(config.api().watermarkFile()), mapper, config.api().initialWatermark());
    configureFile();
    if ("api".equalsIgnoreCase(config.mode())) {
      HttpClient http =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofMillis(config.api().timeoutMs()))
              .build();
      client =
          new EsusApiClient(
              config.api(), http, mapper, new OAuth2TokenProvider(config.api(), http, mapper));
      apiRoute(
          "requests",
          CanonicalBatch.REGULATION_REQUEST,
          config.api().requestsPath(),
          config.api().requestIdPath());
      apiRoute(
          "events",
          CanonicalBatch.REGULATION_STATUS,
          config.api().eventsPath(),
          config.api().eventIdPath());
    }
  }

  private void apiRoute(String name, String entityType, String path, String idPath) {
    from("timer:esus-"
            + name
            + "?period="
            + config.api().pollIntervalMs()
            + "&delay="
            + config.api().initialDelayMs())
        .routeId("esus-api-" + name)
        .process(e -> poll(e, entityType, path, idPath))
        .split(body())
        .to(ConnectorRuntime.INGEST)
        .end()
        .process(
            e -> watermarks.advance(entityType, e.getProperty("esusMaxUpdated", String.class)));
  }

  private void poll(Exchange exchange, String entityType, String path, String idPath) {
    String since = watermarks.get(entityType);
    List<RawMessage> messages = new ArrayList<>();
    String[] max = {null};
    int n =
        client.listSince(
            path,
            since,
            item -> {
              String id = JsonPaths.text(item, idPath);
              String updated = JsonPaths.text(item, config.api().updatedAtPath());
              byte[] json = toBytes(item);
              messages.add(
                  new RawMessage(
                      id == null || id.isBlank() ? Hashes.sha256Hex(json).substring(0, 16) : id,
                      updated == null || updated.isBlank()
                          ? Hashes.sha256Hex(json).substring(0, 16)
                          : updated,
                      entityType,
                      EsusConnector.JSON,
                      json,
                      Map.of(EsusConnector.META_MODE, "api", "path", path),
                      Instant.now()));
              if (updated != null && (max[0] == null || updated.compareTo(max[0]) > 0)) {
                max[0] = updated;
              }
            });
    LOG.infof("e-SUS Regulação %s: %d item(ns) desde %s", path, n, since);
    exchange.setProperty("esusMaxUpdated", max[0]);
    exchange.getIn().setHeader(PipelineHeaders.CORRELATION_ID, Ids.correlation());
    exchange.getIn().setBody(messages);
  }

  private void configureFile() {
    from("file:"
            + config.file().inputDir()
            + "?delay="
            + config.file().pollDelayMs()
            + "&readLock=changed&readLockCheckInterval=500&readLockMinAge=500"
            + "&move=.done/${date:now:yyyyMMdd}/${file:name}&moveFailed=.error/${file:name}"
            + "&includeExt=json,JSON,csv,CSV")
        .routeId("esus-file-source")
        .log(LoggingLevel.INFO, "exportação e-SUS Regulação recebida: ${file:name}")
        .process(this::splitFile)
        .split(body())
        .to(ConnectorRuntime.INGEST)
        .end();
  }

  private void splitFile(Exchange exchange) {
    String fileName = exchange.getIn().getHeader(Exchange.FILE_NAME_ONLY, String.class);
    String lower = fileName.toLowerCase(Locale.ROOT);
    String entityType =
        lower.startsWith("evento") || lower.startsWith("status") || lower.startsWith("mudanca")
            ? CanonicalBatch.REGULATION_STATUS
            : CanonicalBatch.REGULATION_REQUEST;
    String idPath =
        CanonicalBatch.REGULATION_STATUS.equals(entityType)
            ? config.api().eventIdPath()
            : config.api().requestIdPath();
    byte[] content = exchange.getIn().getBody(byte[].class);
    List<JsonNode> items = new ArrayList<>();
    if (lower.endsWith(".json")) {
      try {
        JsonNode root = mapper.readTree(content);
        JsonNode list = root.isArray() ? root : JsonPaths.at(root, config.api().itemsPath());
        if (list != null && list.isArray()) list.forEach(items::add);
        else if (root.isObject()) items.add(root);
      } catch (IOException e) {
        throw new UncheckedIOException("JSON exportado inválido: " + fileName, e);
      }
    } else {
      for (Map<String, String> row :
          new DelimitedParser(config.file().delimiter().charAt(0), true, List.of())
              .parse(content, Charset.forName(config.file().charset()))) {
        items.add(mapper.valueToTree(row));
      }
    }
    List<RawMessage> messages = new ArrayList<>();
    for (JsonNode item : items) {
      String id = JsonPaths.text(item, idPath);
      String updated = JsonPaths.text(item, config.api().updatedAtPath());
      byte[] json = toBytes(item);
      messages.add(
          new RawMessage(
              id == null || id.isBlank() ? Hashes.sha256Hex(json).substring(0, 16) : id,
              updated == null || updated.isBlank()
                  ? Hashes.sha256Hex(json).substring(0, 16)
                  : updated,
              entityType,
              EsusConnector.JSON,
              json,
              Map.of(EsusConnector.META_MODE, "file", EsusConnector.META_FILE, fileName),
              Instant.now()));
    }
    exchange.getIn().setHeader(PipelineHeaders.CORRELATION_ID, Ids.correlation());
    exchange.getIn().setBody(messages);
  }

  private byte[] toBytes(JsonNode node) {
    try {
      return mapper.writeValueAsBytes(node);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }
}
