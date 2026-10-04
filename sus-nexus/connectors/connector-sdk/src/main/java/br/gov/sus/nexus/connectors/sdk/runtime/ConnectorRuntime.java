package br.gov.sus.nexus.connectors.sdk.runtime;

import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.Connector;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.api.FailedMessage;
import br.gov.sus.nexus.connectors.sdk.api.PublishResult;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.RetryDecision;
import br.gov.sus.nexus.connectors.sdk.api.ValidationException;
import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.mapping.MappingException;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import br.gov.sus.nexus.connectors.sdk.raw.RawMessageRef;
import br.gov.sus.nexus.connectors.sdk.raw.RawMessageStore;
import br.gov.sus.nexus.connectors.sdk.retry.DeadLetterHandler;
import br.gov.sus.nexus.connectors.sdk.retry.RetryPolicy;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.jboss.logging.Logger;

/**
 * Pipeline padrão (PLANO §7.1), em Java DSL:
 *
 * <pre>
 * direct:connector-ingest (RawMessage)
 *   → raw store (SHA-256) → ledger(received)
 *   → transform (mapping_version) → ledger(transformed)
 *   → validate → ledger(validated)
 *   → publish (core) → ledger(published)
 * erro transitório → retry (Connector.retry → RetryPolicy, backoff exponencial)
 * erro permanente ou retry esgotado → DLQ (DeadLetterHandler) + ledger(dead_lettered)
 * </pre>
 *
 * As rotas de fonte de cada conector (file, sql, timer...) entregam {@link RawMessage} em {@link
 * #INGEST}. Mensagens processadas são encaminhadas a {@link #PROCESSED} (ponto de extensão).
 */
@ApplicationScoped
public class ConnectorRuntime extends RouteBuilder {

  public static final String INGEST = "direct:connector-ingest";
  public static final String PROCESSED = "direct:connector-processed";
  public static final String DEAD_LETTERED = "direct:connector-dead-lettered";
  public static final String ROUTE_ID = "connector-pipeline";

  private static final Logger LOG = Logger.getLogger(ConnectorRuntime.class);

  private final Connector connector;
  private final RawMessageStore rawStore;
  private final IntegrationMessageLedger ledger;
  private final ConnectorMetrics metrics;
  private final DeadLetterHandler deadLetters;
  private final RetryPolicy retryPolicy;

  @Inject
  public ConnectorRuntime(
      Connector connector,
      RawMessageStore rawStore,
      IntegrationMessageLedger ledger,
      ConnectorMetrics metrics,
      DeadLetterHandler deadLetters,
      RetryPolicy retryPolicy) {
    this.connector = connector;
    this.rawStore = rawStore;
    this.ledger = ledger;
    this.metrics = metrics;
    this.deadLetters = deadLetters;
    this.retryPolicy = retryPolicy;
  }

  @Override
  public void configure() {
    connector.descriptor().validate();

    // Erros transitórios: redelivery decidida por Connector.retry(); o atraso vem da decisão.
    onException(Exception.class)
        .retryWhile(method(this, "shouldRetry"))
        .handled(true)
        .process(this::toDeadLetter)
        .to(DEAD_LETTERED);

    from(INGEST)
        .routeId(ROUTE_ID)
        .process(this::receive)
        .process(this::transform)
        .process(this::validate)
        .process(this::publish)
        .process(this::complete)
        .to(PROCESSED);

    from(PROCESSED)
        .routeId("connector-processed")
        .log(LoggingLevel.DEBUG, "processada ${header.SusMessageId}");
    from(DEAD_LETTERED)
        .routeId("connector-dead-lettered")
        .log(LoggingLevel.DEBUG, "dead letter ${header.SusMessageId}");
  }

  /** Predicado do {@code retryWhile}: consulta {@link Connector#retry(FailedMessage)}. */
  public boolean shouldRetry(Exchange exchange) {
    Exception error = exchange.getException();
    if (error == null) error = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
    IntegrationMessage message =
        exchange.getProperty("SusIntegrationMessage", IntegrationMessage.class);
    int redeliveries = exchange.getIn().getHeader(Exchange.REDELIVERY_COUNTER, 0, Integer.class);
    String stage = exchange.getIn().getHeader(PipelineHeaders.STAGE, "unknown", String.class);
    boolean permanent = isPermanent(error);
    if (message != null) {
      message.fail(
          stage,
          error == null ? "unknown" : error.getClass().getSimpleName(),
          Pii.maskText(String.valueOf(error == null ? null : error.getMessage())));
      ledger.save(message);
    }
    int attempts = message != null ? message.attempts() : redeliveries + 1;
    RetryDecision decision =
        connector.retry(
            new FailedMessage(
                message == null ? null : message.id(), stage, attempts, error, permanent));
    if (decision.retry()) {
      exchange.getIn().setHeader(Exchange.REDELIVERY_DELAY, decision.delay().toMillis());
      if (message != null) {
        message.transitionTo(IntegrationMessageStatus.REPROCESSING);
        ledger.save(message);
      }
      LOG.infof(
          "retry de %s em %s (%s)",
          message == null ? "?" : message.id(), decision.delay(), decision.reason());
      return true;
    }
    return false;
  }

  private static boolean isPermanent(Throwable error) {
    if (error == null) return false;
    if (error instanceof ConnectorException ce) return !ce.isTransient();
    if (error instanceof MappingException || error instanceof IllegalArgumentException) return true;
    Throwable cause = error.getCause();
    return cause != null && cause != error && isPermanent(cause);
  }

  private void receive(Exchange exchange) {
    RawMessage raw = exchange.getIn().getBody(RawMessage.class);
    if (raw == null) {
      throw ConnectorException.permanent("receive", "corpo não é RawMessage", null);
    }
    exchange.getIn().setHeader(PipelineHeaders.STAGE, "receive");
    exchange.getIn().setHeader(PipelineHeaders.STARTED_AT, Instant.now());
    String correlationId =
        exchange.getIn().getHeader(PipelineHeaders.CORRELATION_ID, Ids.correlation(), String.class);
    String connectorId = connector.descriptor().connectorId();
    IntegrationMessage message =
        IntegrationMessage.received(
            connectorId,
            connector.descriptor().sourceSystem(),
            raw.sourceRecordId(),
            raw.sourceRecordVersion(),
            raw.entityType(),
            null,
            null,
            correlationId);
    RawMessageRef ref = rawStore.store(connectorId, message.id(), raw);
    message.rawRef(ref.uri(), ref.sha256());
    ledger.save(message);
    metrics.received(connectorId, raw.entityType());
    exchange.setProperty("SusIntegrationMessage", message);
    exchange.setProperty(PipelineHeaders.RAW_MESSAGE, raw);
    exchange.getIn().setHeader(PipelineHeaders.MESSAGE_ID, message.id());
    exchange.getIn().setHeader(PipelineHeaders.CORRELATION_ID, correlationId);
    exchange.getIn().setHeader(PipelineHeaders.ENTITY_TYPE, raw.entityType());
  }

  private void transform(Exchange exchange) {
    exchange.getIn().setHeader(PipelineHeaders.STAGE, "transform");
    IntegrationMessage message =
        exchange.getProperty("SusIntegrationMessage", IntegrationMessage.class);
    RawMessage raw = exchange.getProperty(PipelineHeaders.RAW_MESSAGE, RawMessage.class);
    CanonicalBatch batch;
    try {
      batch = connector.transform(raw);
    } catch (MappingException e) {
      throw ConnectorException.permanent("transform", e.getMessage(), e);
    }
    advance(message, IntegrationMessageStatus.TRANSFORMED);
    Map<String, String> attrs = new HashMap<>(batch.attributes());
    attrs.put(CanonicalBatch.CORRELATION_ID, message.correlationId());
    exchange
        .getIn()
        .setBody(
            new CanonicalBatch(batch.entityType(), batch.mappingVersion(), batch.records(), attrs));
  }

  private void validate(Exchange exchange) {
    exchange.getIn().setHeader(PipelineHeaders.STAGE, "validate");
    IntegrationMessage message =
        exchange.getProperty("SusIntegrationMessage", IntegrationMessage.class);
    CanonicalBatch batch = exchange.getIn().getBody(CanonicalBatch.class);
    ValidationReport report = connector.validate(batch);
    if (!report.isValid()) {
      throw new ValidationException(report);
    }
    advance(message, IntegrationMessageStatus.VALIDATED);
  }

  private void publish(Exchange exchange) {
    exchange.getIn().setHeader(PipelineHeaders.STAGE, "publish");
    IntegrationMessage message =
        exchange.getProperty("SusIntegrationMessage", IntegrationMessage.class);
    CanonicalBatch batch = exchange.getIn().getBody(CanonicalBatch.class);
    PublishResult result = connector.publish(batch);
    if (!result.allPublished()) {
      throw ConnectorException.transientError(
          "publish", "publicação parcial: " + result.failed() + " falha(s)", null);
    }
    advance(message, IntegrationMessageStatus.PUBLISHED);
    exchange.setProperty("SusPublishResult", result);
  }

  private void complete(Exchange exchange) {
    IntegrationMessage message =
        exchange.getProperty("SusIntegrationMessage", IntegrationMessage.class);
    Instant started = exchange.getIn().getHeader(PipelineHeaders.STARTED_AT, Instant.class);
    String connectorId = connector.descriptor().connectorId();
    metrics.processed(connectorId, message.entityType());
    if (started != null) metrics.latency(connectorId, Duration.between(started, Instant.now()));
    exchange.getIn().setHeader(PipelineHeaders.STAGE, "done");
  }

  private void toDeadLetter(Exchange exchange) {
    Exception error = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
    if (error == null) error = exchange.getException();
    IntegrationMessage message =
        exchange.getProperty("SusIntegrationMessage", IntegrationMessage.class);
    String stage = exchange.getIn().getHeader(PipelineHeaders.STAGE, "unknown", String.class);
    if (message == null) {
      metrics.failed(connector.descriptor().connectorId(), stage);
      LOG.errorf(
          "falha antes do registro no ledger (%s): %s", stage, Pii.maskText(String.valueOf(error)));
      return;
    }
    FailedMessage failed =
        new FailedMessage(message.id(), stage, message.attempts(), error, isPermanent(error));
    deadLetters.handle(message, failed, connector.descriptor().owner());
  }

  private void advance(IntegrationMessage message, IntegrationMessageStatus next) {
    if (message.status() == IntegrationMessageStatus.FAILED) {
      // Em redelivery o estado volta a avançar a partir da etapa que falhou.
      message.transitionTo(IntegrationMessageStatus.REPROCESSING);
    }
    if (message.status() != next) {
      message.transitionTo(next);
    }
    ledger.save(message);
  }
}
