package br.gov.sus.nexus.connectors.ris;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Parser;
import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Receiver;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import br.gov.sus.nexus.connectors.sdk.runtime.ConnectorRuntime;
import br.gov.sus.nexus.connectors.sdk.runtime.PipelineHeaders;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import ca.uhn.hl7v2.AcknowledgmentCode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;

/**
 * Rotas de fonte do RIS/PACS: {@code mllp://host:port} e {@code file:data/ris/in} ({@code *.hl7}) →
 * {@link #RECEIVE} ({@link Hl7Receiver}, ACK store-and-forward); {@code file:data/ris/dicom}
 * ({@code *.json|*.csv} de metadados exportados do PACS) → {@link #DICOM} → pipeline ({@code
 * exam_result}, um registro por estudo). Métricas {@code connector_ris_messages_total{kind}} e
 * {@code connector_ris_ack_total{type}}.
 */
@ApplicationScoped
public class RisRoutes extends RouteBuilder {

  public static final String RECEIVE = "direct:ris-hl7-receive";
  public static final String DICOM = "direct:ris-dicom-receive";

  private final RisConfig config;
  private final RisConnector connector;
  private final ProducerTemplate producer;
  private final ConnectorMetrics metrics;
  private Hl7Receiver receiver;

  @Inject
  public RisRoutes(
      RisConfig config,
      RisConnector connector,
      ProducerTemplate producer,
      ConnectorMetrics metrics) {
    this.config = config;
    this.connector = connector;
    this.producer = producer;
    this.metrics = metrics;
  }

  @PostConstruct
  void init() {
    this.receiver =
        new Hl7Receiver(
            connector.parser(),
            connector.charset(),
            RisConnector.CONNECTOR_ID,
            RisConnector.METRIC_ACK,
            producer,
            metrics,
            (type, trigger, message) -> RisConnector.entityTypeOf(type, trigger),
            (message, type, trigger) -> connector.orderIdOf(message));
  }

  @Override
  public void configure() {
    if (config.mllp().enabled()) {
      from("mllp://"
              + config.mllp().host()
              + ":"
              + config.mllp().port()
              + "?autoAck=true&hl7Headers=false&charsetName="
              + config.charset()
              + "&receiveTimeout="
              + config.mllp().receiveTimeoutMs())
          .routeId("ris-mllp-source")
          .setHeader(Hl7Receiver.HEADER_TRANSPORT, constant("mllp"))
          .to(RECEIVE);
    }

    from("file:"
            + config.file().inputDir()
            + "?delay="
            + config.file().pollDelayMs()
            + "&readLock=changed&readLockCheckInterval=500&readLockMinAge=500"
            + "&move=.done/${date:now:yyyyMMdd}/${file:name}&moveFailed=.error/${file:name}"
            + "&includeExt=hl7,HL7,txt,TXT")
        .routeId("ris-file-source")
        .log(LoggingLevel.INFO, "arquivo HL7 do RIS recebido: ${file:name}")
        .setHeader(Hl7Receiver.HEADER_TRANSPORT, constant("file"))
        .process(
            e -> {
              String text = new String(e.getIn().getBody(byte[].class), connector.charset());
              e.getIn().setBody(Hl7Parser.splitMessages(text));
            })
        .split(body())
        .to(RECEIVE)
        .end();

    from(RECEIVE).routeId("ris-hl7-receive").process(this::receive);

    if (config.dicom().enabled()) {
      from("file:"
              + config.dicom().inputDir()
              + "?delay="
              + config.dicom().pollDelayMs()
              + "&readLock=changed&readLockCheckInterval=500&readLockMinAge=500"
              + "&move=.done/${date:now:yyyyMMdd}/${file:name}&moveFailed=.error/${file:name}"
              + "&includeExt=json,JSON,csv,CSV")
          .routeId("ris-dicom-source")
          .log(LoggingLevel.INFO, "export de metadados DICOM recebido: ${file:name}")
          .to(DICOM);
    }

    from(DICOM).routeId("ris-dicom-receive").process(this::dicom);
  }

  void receive(Exchange exchange) {
    AcknowledgmentCode code = receiver.receive(exchange);
    if (code == AcknowledgmentCode.AA) {
      String entity =
          exchange.getIn().getHeader(Hl7Receiver.HEADER_ENTITY, "unknown", String.class);
      metrics.counter(
          RisConnector.METRIC_MESSAGES,
          1,
          "connector_id",
          RisConnector.CONNECTOR_ID,
          "kind",
          entity);
    }
  }

  /** Arquivo de metadados DICOM → RawMessage (id = nome do arquivo, versão = prefixo do SHA). */
  void dicom(Exchange exchange) {
    byte[] content = exchange.getIn().getBody(byte[].class);
    String name = exchange.getIn().getHeader(Exchange.FILE_NAME, "dicom-export", String.class);
    String sha = Hashes.sha256Hex(content);
    String contentType =
        name.toLowerCase(Locale.ROOT).endsWith(".csv")
            ? RisConnector.DICOM_CSV
            : RisConnector.DICOM_JSON;
    RawMessage raw =
        new RawMessage(
            name,
            sha.substring(0, 16),
            CanonicalBatch.EXAM_RESULT,
            contentType,
            content,
            Map.of(
                RisConnector.META_SOURCE,
                RisConnector.META_SOURCE_DICOM,
                Hl7Receiver.META_TRANSPORT,
                "file-dicom-metadata"),
            Instant.now());
    producer.sendBodyAndHeader(
        ConnectorRuntime.INGEST, raw, PipelineHeaders.CORRELATION_ID, Ids.correlation());
    metrics.counter(
        RisConnector.METRIC_MESSAGES,
        1,
        "connector_id",
        RisConnector.CONNECTOR_ID,
        "kind",
        "dicom_study");
  }
}
