package br.gov.sus.nexus.connectors.cnes;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.runtime.ConnectorRuntime;
import br.gov.sus.nexus.connectors.sdk.runtime.PipelineHeaders;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;

/** Rota de fonte: arquivos tbEstabelecimento*.csv|dbf → RawMessage → pipeline. */
@ApplicationScoped
public class CnesRoutes extends RouteBuilder {

  private static final Pattern COMPETENCE = Pattern.compile("(20\\d{2}(0[1-9]|1[0-2]))");

  private final CnesConfig config;

  @Inject
  public CnesRoutes(CnesConfig config) {
    this.config = config;
  }

  @Override
  public void configure() {
    from("file:"
            + config.inputDir()
            + "?delay="
            + config.pollDelayMs()
            + "&readLock=changed&readLockCheckInterval=500&readLockMinAge=500"
            + "&move=.done/${date:now:yyyyMMdd}/${file:name}&moveFailed=.error/${file:name}"
            + "&includeExt=csv,dbf,CSV,DBF")
        .routeId("cnes-file-source")
        .log(LoggingLevel.INFO, "arquivo CNES recebido: ${file:name}")
        .process(this::toRawMessage)
        .to(ConnectorRuntime.INGEST);
  }

  private void toRawMessage(Exchange exchange) {
    String fileName = exchange.getIn().getHeader(Exchange.FILE_NAME_ONLY, String.class);
    byte[] content = exchange.getIn().getBody(byte[].class);
    Map<String, String> meta = new LinkedHashMap<>();
    meta.put("file", fileName);
    Matcher m = COMPETENCE.matcher(fileName);
    if (m.find()) meta.put("competence", m.group(1));
    String version = Hashes.sha256Hex(content).substring(0, 16);
    exchange.getIn().setHeader(PipelineHeaders.CORRELATION_ID, Ids.correlation());
    exchange
        .getIn()
        .setBody(
            new RawMessage(
                fileName,
                version,
                "health_unit",
                "application/octet-stream",
                content,
                meta,
                Instant.now()));
  }
}
