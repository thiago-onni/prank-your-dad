package br.gov.sus.nexus.connectors.lis;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Parser;
import br.gov.sus.nexus.connectors.sdk.hl7.Hl7Receiver;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import ca.uhn.hl7v2.model.Message;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;

/**
 * Rotas de fonte do LIS.
 *
 * <ul>
 *   <li>{@code mllp://host:port} (quando {@code lis.mllp.enabled}) → {@link #RECEIVE};
 *   <li>{@code file:data/lis/in} ({@code *.hl7}, uma ou várias mensagens por arquivo) → {@link
 *       #RECEIVE};
 *   <li>{@link #RECEIVE}: {@link Hl7Receiver} do SDK (parse tolerante → RawMessage → pipeline
 *       síncrono → ACK store-and-forward: AA persistido, AR não suportado, AE malformado).
 * </ul>
 *
 * Métrica {@code connector_lis_ack_total{type}}.
 */
@ApplicationScoped
public class LisRoutes extends RouteBuilder {

  public static final String RECEIVE = "direct:lis-hl7-receive";
  public static final String HEADER_ACK_TYPE = Hl7Receiver.HEADER_ACK_TYPE;
  public static final String HEADER_ACK_STRING = Hl7Receiver.HEADER_ACK_STRING;
  public static final String HEADER_ACK = Hl7Receiver.HEADER_ACK;
  public static final String HEADER_ENTITY = Hl7Receiver.HEADER_ENTITY;
  public static final String HEADER_CONTROL_ID = Hl7Receiver.HEADER_CONTROL_ID;
  public static final String METRIC_ACK = "connector_lis_ack_total";

  private final LisConfig config;
  private final LisConnector connector;
  private final ProducerTemplate producer;
  private final ConnectorMetrics metrics;
  private Hl7Receiver receiver;

  @Inject
  public LisRoutes(
      LisConfig config,
      LisConnector connector,
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
            connector.descriptor().connectorId(),
            METRIC_ACK,
            producer,
            metrics,
            (type, trigger, message) -> entityTypeOf(type, trigger),
            (message, type, trigger) -> orderIdOf(message));
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
          .routeId("lis-mllp-source")
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
        .routeId("lis-file-source")
        .log(LoggingLevel.INFO, "arquivo HL7 recebido: ${file:name}")
        .setHeader(Hl7Receiver.HEADER_TRANSPORT, constant("file"))
        .process(
            e -> {
              String text = new String(e.getIn().getBody(byte[].class), connector.charset());
              e.getIn().setBody(Hl7Parser.splitMessages(text));
            })
        .split(body())
        .to(RECEIVE)
        .end();

    from(RECEIVE).routeId("lis-hl7-receive").process(this::receive);
  }

  /** Parse → RawMessage → pipeline → ACK no corpo e nos headers MLLP (via {@link Hl7Receiver}). */
  void receive(Exchange exchange) {
    receiver.receive(exchange);
  }

  static String entityTypeOf(String type, String trigger) {
    if ("ORM".equalsIgnoreCase(type) && "O01".equalsIgnoreCase(trigger))
      return CanonicalBatch.EXAM_ORDER;
    if ("ORU".equalsIgnoreCase(type) && "R01".equalsIgnoreCase(trigger))
      return CanonicalBatch.EXAM_RESULT;
    return null;
  }

  /**
   * Nº do pedido: placer (ORC-2/OBR-2) ou filler (ORC-3/OBR-3) conforme {@code
   * lis.order.id-source}.
   */
  private String orderIdOf(Message message) {
    boolean filler = "filler".equalsIgnoreCase(config.order().idSource());
    return filler
        ? Hl7Receiver.firstTerser(message, "/.ORC-3-1", "/.OBR-3-1", "/.ORC-2-1", "/.OBR-2-1")
        : Hl7Receiver.firstTerser(message, "/.ORC-2-1", "/.OBR-2-1", "/.ORC-3-1", "/.OBR-3-1");
  }
}
