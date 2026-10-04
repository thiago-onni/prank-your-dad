package br.gov.sus.nexus.connectors.template;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.runtime.ConnectorRuntime;
import br.gov.sus.nexus.connectors.sdk.runtime.PipelineHeaders;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Rota de fonte de exemplo: diretório → RawMessage → pipeline padrão do SDK. */
@ApplicationScoped
public class ExampleRoutes extends RouteBuilder {

  @ConfigProperty(name = "example.input-dir", defaultValue = "data/example/in")
  String inputDir;

  @Override
  public void configure() {
    from("file:"
            + inputDir
            + "?delay=5000&readLock=changed&move=.done/${file:name}&moveFailed=.error/${file:name}&includeExt=csv")
        .routeId("example-file-source")
        .process(this::toRawMessage)
        .to(ConnectorRuntime.INGEST);
  }

  private void toRawMessage(Exchange exchange) {
    String fileName = exchange.getIn().getHeader(Exchange.FILE_NAME_ONLY, String.class);
    byte[] content = exchange.getIn().getBody(byte[].class);
    exchange.getIn().setHeader(PipelineHeaders.CORRELATION_ID, Ids.correlation());
    exchange
        .getIn()
        .setBody(
            new RawMessage(
                fileName,
                Hashes.sha256Hex(content).substring(0, 16),
                "citizen",
                "text/csv",
                content,
                Map.of("file", fileName),
                Instant.now()));
  }
}
