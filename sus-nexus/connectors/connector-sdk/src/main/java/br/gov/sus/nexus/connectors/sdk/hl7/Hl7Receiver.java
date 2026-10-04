package br.gov.sus.nexus.connectors.sdk.hl7;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import br.gov.sus.nexus.connectors.sdk.runtime.ConnectorRuntime;
import br.gov.sus.nexus.connectors.sdk.runtime.PipelineHeaders;
import br.gov.sus.nexus.connectors.sdk.util.Hashes;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;

/**
 * Recepção HL7 v2 compartilhada (MLLP, arquivo ou {@code direct}): parse tolerante → {@link
 * RawMessage} → pipeline do SDK (síncrono) → ACK no corpo e nos headers MLLP do Camel.
 *
 * <p>Semântica do ACK (store-and-forward): {@code AA} quando a mensagem foi parseada e persistida
 * na raw zone/ledger (falhas posteriores de publicação ficam com retry/DLQ do pipeline e NÃO geram
 * NAK, para a origem não reenviar indefinidamente); {@code AR} para tipo de mensagem não suportado;
 * {@code AE} para mensagem malformada ou falha ao persistir. Métrica {@code <ackMetric>{type}}.
 */
public final class Hl7Receiver {

  public static final String HL7_CONTENT_TYPE = "x-application/hl7-v2+er7";
  public static final String HEADER_ACK_TYPE = "CamelMllpAcknowledgementType";
  public static final String HEADER_ACK_STRING = "CamelMllpAcknowledgementString";
  public static final String HEADER_ACK = "CamelMllpAcknowledgement";
  public static final String HEADER_TRANSPORT = "SusHl7Transport";
  public static final String HEADER_ENTITY = "SusHl7EntityType";
  public static final String HEADER_CONTROL_ID = "SusHl7ControlId";
  public static final String HEADER_TRIGGER = "SusHl7Trigger";
  public static final String META_MESSAGE_TYPE = "message_type";
  public static final String META_CONTROL_ID = "control_id";
  public static final String META_TRANSPORT = "transport";
  public static final String META_TRIGGER = "trigger";

  private static final Logger LOG = Logger.getLogger(Hl7Receiver.class);

  /** Decide o {@code entity_type} do pipeline a partir de MSH-9; {@code null} = não suportado. */
  @FunctionalInterface
  public interface Classifier {
    String entityTypeOf(String messageType, String trigger, Message message);
  }

  /** Identificador do registro na origem (nº do pedido, nº do atendimento...). */
  @FunctionalInterface
  public interface RecordIdResolver {
    String recordIdOf(Message message, String messageType, String trigger);
  }

  private final Hl7Parser parser;
  private final Charset charset;
  private final String connectorId;
  private final String ackMetric;
  private final ProducerTemplate producer;
  private final ConnectorMetrics metrics;
  private final Classifier classifier;
  private final RecordIdResolver recordIds;

  public Hl7Receiver(
      Hl7Parser parser,
      Charset charset,
      String connectorId,
      String ackMetric,
      ProducerTemplate producer,
      ConnectorMetrics metrics,
      Classifier classifier,
      RecordIdResolver recordIds) {
    this.parser = parser;
    this.charset = charset;
    this.connectorId = connectorId;
    this.ackMetric = ackMetric;
    this.producer = producer;
    this.metrics = metrics;
    this.classifier = classifier;
    this.recordIds = recordIds;
  }

  /** Processa o corpo do exchange (bytes ou texto) e deixa o ACK no corpo/headers. */
  public AcknowledgmentCode receive(Exchange exchange) {
    Object body = exchange.getIn().getBody();
    String text =
        body instanceof byte[] bytes
            ? new String(bytes, charset)
            : exchange.getIn().getBody(String.class);
    String transport = exchange.getIn().getHeader(HEADER_TRANSPORT, "direct", String.class);
    Message message;
    try {
      message = parser.parse(text);
    } catch (HL7Exception | RuntimeException e) {
      LOG.warnf("HL7 malformado (%s): %s", transport, Pii.maskText(String.valueOf(e.getMessage())));
      return ack(
          exchange,
          null,
          text,
          AcknowledgmentCode.AE,
          "mensagem HL7 malformada: " + e.getMessage());
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
      return ack(exchange, message, text, AcknowledgmentCode.AE, "MSH ilegível");
    }
    String entityType = classifier.entityTypeOf(type, trigger, message);
    if (entityType == null) {
      return ack(
          exchange,
          message,
          text,
          AcknowledgmentCode.AR,
          "tipo de mensagem não suportado: " + type + "^" + trigger);
    }
    String recordId = nz(recordIds.recordIdOf(message, type, trigger));
    byte[] content = Hl7Parser.normalize(text).getBytes(charset);
    Map<String, String> meta = new LinkedHashMap<>();
    meta.put(META_MESSAGE_TYPE, type + "^" + trigger);
    meta.put(META_TRIGGER, trigger);
    meta.put(META_CONTROL_ID, controlId);
    meta.put(META_TRANSPORT, transport);
    RawMessage raw =
        new RawMessage(
            recordId.isBlank() ? controlId : recordId,
            controlId.isBlank() ? Hashes.sha256Hex(content).substring(0, 16) : controlId,
            entityType,
            HL7_CONTENT_TYPE + "; charset=" + charset.name().toLowerCase(Locale.ROOT),
            content,
            meta,
            Instant.now());
    try {
      producer.sendBodyAndHeader(
          ConnectorRuntime.INGEST, raw, PipelineHeaders.CORRELATION_ID, Ids.correlation());
    } catch (RuntimeException e) {
      LOG.errorf(
          "falha ao persistir mensagem %s: %s",
          controlId, Pii.maskText(String.valueOf(e.getMessage())));
      return ack(
          exchange, message, text, AcknowledgmentCode.AE, "falha ao persistir: " + e.getMessage());
    }
    exchange.getIn().setHeader(HEADER_ENTITY, entityType);
    exchange.getIn().setHeader(HEADER_CONTROL_ID, controlId);
    exchange.getIn().setHeader(HEADER_TRIGGER, trigger);
    return ack(exchange, message, text, AcknowledgmentCode.AA, null);
  }

  private AcknowledgmentCode ack(
      Exchange exchange, Message parsed, String raw, AcknowledgmentCode code, String error) {
    String ack =
        parsed == null ? Hl7Acks.manualAck(raw, code, error) : Hl7Acks.ack(parsed, code, error);
    exchange.getIn().setHeader(HEADER_ACK_TYPE, code.name());
    exchange.getIn().setHeader(HEADER_ACK_STRING, ack);
    exchange.getIn().setHeader(HEADER_ACK, ack.getBytes(charset));
    exchange.getIn().setBody(ack);
    metrics.counter(ackMetric, 1, "connector_id", connectorId, "type", code.name());
    return code;
  }

  /** Primeiro valor não vazio entre caminhos Terser (ex.: {@code /.PV1-19-1}). */
  public static String firstTerser(Message message, String... paths) {
    try {
      Terser t = new Terser(message);
      for (String p : paths) {
        String v = nz(t.get(p));
        if (!v.isBlank()) return v;
      }
    } catch (HL7Exception | RuntimeException e) {
      return "";
    }
    return "";
  }

  private static String nz(String v) {
    return v == null ? "" : v.trim();
  }
}
