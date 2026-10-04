package br.gov.sus.nexus.connectors.terminology;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.parse.TableLayout;
import br.gov.sus.nexus.connectors.sdk.runtime.ConnectorRuntime;
import br.gov.sus.nexus.connectors.sdk.runtime.PipelineHeaders;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;

/** Rota de fonte: diretório de entrada → {@link RawMessage} → pipeline padrão do SDK. */
@ApplicationScoped
public class TerminologyRoutes extends RouteBuilder {

  private static final Pattern COMPETENCE = Pattern.compile("(20\\d{2}(0[1-9]|1[0-2]))");

  private final TerminologyConfig config;
  private final TerminologyConnector connector;

  @Inject
  public TerminologyRoutes(TerminologyConfig config, TerminologyConnector connector) {
    this.config = config;
    this.connector = connector;
  }

  @Override
  public void configure() {
    from("file:"
            + config.inputDir()
            + "?delay="
            + config.pollDelayMs()
            + "&readLock=changed&readLockCheckInterval=500&readLockMinAge=500"
            + "&move=.done/${date:now:yyyyMMdd}/${file:name}&moveFailed=.error/${file:name}"
            + "&includeExt=txt,csv,TXT,CSV")
        .routeId("terminology-file-source")
        .log(LoggingLevel.INFO, "arquivo de terminologia recebido: ${file:name}")
        .process(this::toRawMessage)
        .to(ConnectorRuntime.INGEST);
  }

  private void toRawMessage(Exchange exchange) {
    String fileName = exchange.getIn().getHeader(Exchange.FILE_NAME_ONLY, String.class);
    byte[] content = exchange.getIn().getBody(byte[].class);
    Optional<TableLayout> layout = connector.layouts().forFile(fileName);
    Map<String, String> meta = new LinkedHashMap<>();
    meta.put(TerminologyConnector.META_FILE, fileName);
    layout.ifPresent(l -> meta.put(TerminologyConnector.META_TABLE, l.name()));
    Matcher m = COMPETENCE.matcher(fileName);
    if (m.find()) meta.put("competence", m.group(1));
    String version = Hashes.sha256Hex(content).substring(0, 16);
    exchange.getIn().setHeader(PipelineHeaders.CORRELATION_ID, Ids.correlation());
    exchange
        .getIn()
        .setBody(
            new RawMessage(fileName, version, "code", "text/plain", content, meta, Instant.now()));
  }
}
