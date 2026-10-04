package br.gov.sus.nexus.connectors.pec;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.parse.DelimitedParser;
import br.gov.sus.nexus.connectors.sdk.runtime.ConnectorRuntime;
import br.gov.sus.nexus.connectors.sdk.runtime.PipelineHeaders;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;

/**
 * Rotas de fonte do PEC.
 *
 * <ul>
 *   <li>{@code file}: {@code cidadaos*.csv} e {@code agendamentos*.csv} → uma {@link RawMessage}
 *       por linha (cabeçalho + linha), id = primeira coluna.
 *   <li>{@code jdbc}: timer → consulta SQL parametrizada por marca d'água ({@code :#watermark}) →
 *       uma {@link RawMessage} (JSON) por linha → avança a marca d'água com {@code dt_atualizado}.
 * </ul>
 */
@ApplicationScoped
public class PecRoutes extends RouteBuilder {

  private final PecConfig config;
  private final ObjectMapper mapper;
  private WatermarkStore watermarks;

  @Inject
  public PecRoutes(PecConfig config, ObjectMapper mapper) {
    this.config = config;
    this.mapper = mapper;
  }

  @Override
  public void configure() {
    if ("jdbc".equalsIgnoreCase(config.mode())) {
      configureJdbc();
    } else {
      configureFile();
    }
  }

  private void configureFile() {
    from("file:"
            + config.file().inputDir()
            + "?delay="
            + config.file().pollDelayMs()
            + "&readLock=changed&readLockCheckInterval=500&readLockMinAge=500"
            + "&move=.done/${date:now:yyyyMMdd}/${file:name}&moveFailed=.error/${file:name}"
            + "&includeExt=csv,CSV")
        .routeId("pec-file-source")
        .log(LoggingLevel.INFO, "arquivo PEC recebido: ${file:name}")
        .process(this::splitCsvIntoRows)
        .split(body())
        .to(ConnectorRuntime.INGEST)
        .end();
  }

  private void configureJdbc() {
    watermarks =
        new WatermarkStore(
            Path.of(config.jdbc().watermarkFile()), mapper, config.jdbc().initialWatermark());
    jdbcRoute("citizens", CanonicalBatch.CITIZEN, config.jdbc().citizensQuery(), "co_seq_cidadao");
    jdbcRoute(
        "appointments",
        CanonicalBatch.APPOINTMENT,
        config.jdbc().appointmentsQuery(),
        "co_seq_agendado");
  }

  private void jdbcRoute(String name, String entityType, String query, String idColumn) {
    from("timer:pec-" + name + "?period=" + config.jdbc().pollIntervalMs() + "&delay=5000")
        .routeId("pec-jdbc-" + name)
        .process(e -> e.getIn().setHeader("watermark", watermarks.get(entityType)))
        .log(
            LoggingLevel.INFO, "PEC jdbc " + name + ": consultando a partir de ${header.watermark}")
        .to("sql:" + query + "?dataSource=#pecDataSource&outputType=SelectList")
        .split(body())
        .process(e -> toJdbcRawMessage(e, entityType, idColumn))
        .to(ConnectorRuntime.INGEST)
        .process(
            e -> watermarks.advance(entityType, e.getIn().getHeader("pecUpdatedAt", String.class)))
        .end();
  }

  @SuppressWarnings("unchecked")
  private void toJdbcRawMessage(Exchange exchange, String entityType, String idColumn) {
    Map<String, Object> row = exchange.getIn().getBody(Map.class);
    Map<String, String> normalized = PecRows.normalize(row);
    String id = normalized.getOrDefault(idColumn, "");
    String updatedAt = normalized.getOrDefault("dt_atualizado", "");
    try {
      byte[] json = mapper.writeValueAsBytes(normalized);
      exchange.getIn().setHeader("pecUpdatedAt", updatedAt);
      exchange.getIn().setHeader(PipelineHeaders.CORRELATION_ID, Ids.correlation());
      exchange
          .getIn()
          .setBody(
              new RawMessage(
                  id,
                  versionOf(updatedAt, json),
                  entityType,
                  PecRows.JSON,
                  json,
                  Map.of(PecConnector.META_MODE, "jdbc"),
                  Instant.now()));
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void splitCsvIntoRows(Exchange exchange) {
    String fileName = exchange.getIn().getHeader(Exchange.FILE_NAME_ONLY, String.class);
    String entityType =
        fileName.toLowerCase(Locale.ROOT).startsWith("agend")
            ? CanonicalBatch.APPOINTMENT
            : CanonicalBatch.CITIZEN;
    Charset charset = Charset.forName(config.file().charset());
    String text = new String(exchange.getIn().getBody(byte[].class), charset);
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    List<RawMessage> messages = new ArrayList<>();
    char delimiter = config.file().delimiter().charAt(0);
    try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
      String header = reader.readLine();
      if (header == null) return;
      List<String> names =
          new DelimitedParser(delimiter, false, List.of())
              .parse(header).get(0).values().stream()
                  .map(n -> n.trim().toLowerCase(Locale.ROOT))
                  .toList();
      DelimitedParser rowParser = new DelimitedParser(delimiter, false, names);
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        List<Map<String, String>> parsed = rowParser.parse(line);
        Map<String, String> row = parsed.isEmpty() ? Map.of() : parsed.get(0);
        String id = names.isEmpty() ? "" : row.getOrDefault(names.get(0), "");
        byte[] content = (header + "\n" + line).getBytes(StandardCharsets.UTF_8);
        messages.add(
            new RawMessage(
                id,
                versionOf(row.get("dt_atualizado"), content),
                entityType,
                PecRows.CSV,
                content,
                Map.of(PecConnector.META_MODE, "file", PecConnector.META_FILE, fileName),
                Instant.now()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    exchange.getIn().setHeader(PipelineHeaders.CORRELATION_ID, Ids.correlation());
    exchange.getIn().setBody(messages);
  }

  private static String versionOf(String updatedAt, byte[] content) {
    if (updatedAt != null && !updatedAt.isBlank()) return updatedAt;
    return Hashes.sha256Hex(content).substring(0, 16);
  }
}
