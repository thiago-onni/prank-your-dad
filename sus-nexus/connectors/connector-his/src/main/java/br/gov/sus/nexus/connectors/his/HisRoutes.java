package br.gov.sus.nexus.connectors.his;

import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Parser;
import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Receiver;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import ca.uhn.hl7v2.AcknowledgmentCode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;

/**
 * Rotas de fonte do HIS: {@code mllp://host:port} (quando {@code his.mllp.enabled}) e {@code
 * file:data/his/in} ({@code *.hl7}, várias mensagens por arquivo) → {@link #RECEIVE} ({@link
 * Hl7Receiver}: parse → RawMessage → pipeline → ACK store-and-forward). Métricas {@code
 * connector_his_adt_total{trigger}} (mensagens aceitas, AA) e {@code
 * connector_his_ack_total{type}}.
 */
@ApplicationScoped
public class HisRoutes extends RouteBuilder {

  public static final String RECEIVE = "direct:his-adt-receive";

  private final HisConfig config;
  private final HisConnector connector;
  private final ProducerTemplate producer;
  private final ConnectorMetrics metrics;
  private Hl7Receiver receiver;

  @Inject
  public HisRoutes(
      HisConfig config,
      HisConnector connector,
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
            HisConnector.CONNECTOR_ID,
            HisConnector.METRIC_ACK,
            producer,
            metrics,
            (type, trigger, message) -> HisConnector.entityTypeOf(type, trigger),
            (message, type, trigger) -> HisConnector.visitIdOf(message));
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
          .routeId("his-mllp-source")
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
        .routeId("his-file-source")
        .log(LoggingLevel.INFO, "arquivo HL7 ADT recebido: ${file:name}")
        .setHeader(Hl7Receiver.HEADER_TRANSPORT, constant("file"))
        .process(
            e -> {
              String text = new String(e.getIn().getBody(byte[].class), connector.charset());
              e.getIn().setBody(Hl7Parser.splitMessages(text));
            })
        .split(body())
        .to(RECEIVE)
        .end();

    from(RECEIVE).routeId("his-adt-receive").process(this::receive);
  }

  void receive(Exchange exchange) {
    AcknowledgmentCode code = receiver.receive(exchange);
    if (code == AcknowledgmentCode.AA) {
      String trigger =
          exchange.getIn().getHeader(Hl7Receiver.HEADER_TRIGGER, "unknown", String.class);
      metrics.counter(
          HisConnector.METRIC_ADT,
          1,
          "connector_id",
          HisConnector.CONNECTOR_ID,
          "trigger",
          trigger.toUpperCase(Locale.ROOT));
    }
  }
}
