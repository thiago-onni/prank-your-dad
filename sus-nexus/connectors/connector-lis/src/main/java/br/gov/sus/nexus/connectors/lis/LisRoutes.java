package br.gov.sus.nexus.connectors.lis;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.runtime.ConnectorRuntime;
import br.gov.sus.nexus.connectors.sdk.runtime.PipelineHeaders;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.jboss.logging.Logger;

/**
 * Rotas de fonte do LIS.
 *
 * <ul>
 *   <li>{@code mllp://host:port} (quando {@code lis.mllp.enabled}) → {@link #RECEIVE};
 *   <li>{@code file:data/lis/in} ({@code *.hl7}, uma ou várias mensagens por arquivo) → {@link
 *       #RECEIVE};
 *   <li>{@link #RECEIVE}: parse tolerante → {@link RawMessage} → pipeline (síncrono) → ACK.
 * </ul>
 *
 * <p>Semântica do ACK (store-and-forward): {@code AA} quando a mensagem foi parseada e persistida
 * na raw zone/ledger (falhas posteriores de publicação ficam com retry/DLQ do pipeline e NÃO geram
 * NAK, para o LIS não reenviar indefinidamente); {@code AR} para tipo de mensagem não suportado;
 * {@code AE} para mensagem malformada ou falha ao persistir.
 */
@ApplicationScoped
public class LisRoutes extends RouteBuilder {

  public static final String RECEIVE = "direct:lis-hl7-receive";
  public static final String HEADER_ACK_TYPE = "CamelMllpAcknowledgementType";
  public static final String HEADER_ACK_STRING = "CamelMllpAcknowledgementString";
  public static final String HEADER_ACK = "CamelMllpAcknowledgement";
  public static final String HEADER_ENTITY = "LisEntityType";
  public static final String HEADER_CONTROL_ID = "LisControlId";
  public static final String METRIC_ACK = "connector_lis_ack_total";

  private static final Logger LOG = Logger.getLogger(LisRoutes.class);

  private final LisConfig config;
  private final LisConnector connector;
  private final ProducerTemplate producer;
  private final br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics metrics;

  @Inject
  public LisRoutes(
      LisConfig config,
      LisConnector connector,
      ProducerTemplate producer,
      br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics metrics) {
    this.config = config;
    this.connector = connector;
    this.producer = producer;
    this.metrics = metrics;
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
          .setHeader("LisTransport", constant("mllp"))
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
        .setHeader("LisTransport", constant("file"))
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

  /** Parse → RawMessage → pipeline → ACK no corpo e nos headers MLLP. */
  void receive(Exchange exchange) {
    Object body = exchange.getIn().getBody();
    Charset charset = connector.charset();
    String text =
        body instanceof byte[] bytes
            ? new String(bytes, charset)
            : exchange.getIn().getBody(String.class);
    String transport = exchange.getIn().getHeader("LisTransport", "direct", String.class);
    Message message;
    try {
      message = connector.parser().parse(text);
    } catch (HL7Exception | RuntimeException e) {
      LOG.warnf("HL7 malformado (%s): %s", transport, Pii.maskText(String.valueOf(e.getMessage())));
      ack(
          exchange,
          null,
          text,
          AcknowledgmentCode.AE,
          "mensagem HL7 malformada: " + e.getMessage());
      return;
    }
    String type;
    String trigger;
    String controlId;
    try {
      Terser t = new Terser(message);
      type = nz(t.get("MSH-9-1"));
      trigger = nz(t.get("MSH-9-2"));
      controlId = nz(t.get("MSH-10"));
    } catch (HL7Exception e) {
      ack(exchange, message, text, AcknowledgmentCode.AE, "MSH ilegível");
      return;
    }
    String entityType = entityTypeOf(type, trigger);
    if (entityType == null) {
      ack(
          exchange,
          message,
          text,
          AcknowledgmentCode.AR,
          "tipo de mensagem não suportado: " + type + "^" + trigger);
      return;
    }
    String orderId = orderIdOf(message);
    byte[] content = Hl7Parser.normalize(text).getBytes(charset);
    RawMessage raw =
        new RawMessage(
            orderId.isBlank() ? controlId : orderId,
            controlId.isBlank() ? Hashes.sha256Hex(content).substring(0, 16) : controlId,
            entityType,
            LisConnector.HL7_CONTENT_TYPE + "; charset=" + charset.name().toLowerCase(Locale.ROOT),
            content,
            Map.of(
                LisConnector.META_MESSAGE_TYPE, type + "^" + trigger,
                LisConnector.META_CONTROL_ID, controlId,
                LisConnector.META_TRANSPORT, transport),
            Instant.now());
    try {
      producer.sendBodyAndHeader(
          ConnectorRuntime.INGEST, raw, PipelineHeaders.CORRELATION_ID, Ids.correlation());
    } catch (RuntimeException e) {
      LOG.errorf(
          "falha ao persistir mensagem %s: %s",
          controlId, Pii.maskText(String.valueOf(e.getMessage())));
      ack(exchange, message, text, AcknowledgmentCode.AE, "falha ao persistir: " + e.getMessage());
      return;
    }
    exchange.getIn().setHeader(HEADER_ENTITY, entityType);
    exchange.getIn().setHeader(HEADER_CONTROL_ID, controlId);
    ack(exchange, message, text, AcknowledgmentCode.AA, null);
  }

  private void ack(
      Exchange exchange, Message parsed, String raw, AcknowledgmentCode code, String error) {
    String ack =
        parsed == null ? Hl7Acks.manualAck(raw, code, error) : Hl7Acks.ack(parsed, code, error);
    exchange.getIn().setHeader(HEADER_ACK_TYPE, code.name());
    exchange.getIn().setHeader(HEADER_ACK_STRING, ack);
    exchange.getIn().setHeader(HEADER_ACK, ack.getBytes(connector.charset()));
    exchange.getIn().setBody(ack);
    metrics.counter(
        METRIC_ACK, 1, "connector_id", connector.descriptor().connectorId(), "type", code.name());
  }

  static String entityTypeOf(String type, String trigger) {
    if ("ORM".equalsIgnoreCase(type) && "O01".equalsIgnoreCase(trigger))
      return CanonicalBatch.EXAM_ORDER;
    if ("ORU".equalsIgnoreCase(type) && "R01".equalsIgnoreCase(trigger))
      return CanonicalBatch.EXAM_RESULT;
    return null;
  }

  private String orderIdOf(Message message) {
    try {
      Terser t = new Terser(message);
      List<String> candidates = new ArrayList<>();
      boolean filler = "filler".equalsIgnoreCase(config.order().idSource());
      for (String base : List.of("/.ORC", "/.OBR")) {
        String placer = nz(t.get(base + "-2-1"));
        String fill = nz(t.get(base + "-3-1"));
        if (filler) {
          candidates.add(fill);
          candidates.add(placer);
        } else {
          candidates.add(placer);
          candidates.add(fill);
        }
      }
      return candidates.stream().filter(c -> !c.isBlank()).findFirst().orElse("");
    } catch (HL7Exception | RuntimeException e) {
      return "";
    }
  }

  private static String nz(String v) {
    return v == null ? "" : v.trim();
  }
}
