package br.gov.sus.nexus.connectors.sdk.retry;

import br.gov.sus.nexus.connectors.sdk.api.FailedMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Após esgotar o retry: marca a mensagem como dead_lettered no ledger, grava na DLQ e conta
 * métrica.
 */
@ApplicationScoped
public class DeadLetterHandler {

  private static final Logger LOG = Logger.getLogger(DeadLetterHandler.class);

  private final DeadLetterSink sink;
  private final IntegrationMessageLedger ledger;
  private final ConnectorMetrics metrics;

  @Inject
  public DeadLetterHandler(
      DeadLetterSink sink, IntegrationMessageLedger ledger, ConnectorMetrics metrics) {
    this.sink = sink;
    this.ledger = ledger;
    this.metrics = metrics;
  }

  public DeadLetter handle(IntegrationMessage message, FailedMessage failed, String owner) {
    String reason =
        Pii.maskText(
            String.valueOf(failed.error() == null ? "desconhecido" : failed.error().getMessage()));
    if (message.status() != IntegrationMessageStatus.FAILED) {
      message.fail(failed.stage(), errorCode(failed), reason);
    }
    message.transitionTo(IntegrationMessageStatus.DEAD_LETTERED);
    ledger.save(message);
    DeadLetter dl =
        new DeadLetter(
            Ids.deadLetter(),
            message.id(),
            message.connectorId(),
            "sus.dlq.v1",
            reason,
            failed.stage(),
            message.attempts(),
            owner,
            message.rawRef(),
            Instant.now());
    sink.accept(dl);
    metrics.failed(message.connectorId(), failed.stage());
    LOG.warnf(
        "mensagem %s enviada à DLQ (%s) após %d tentativa(s): %s",
        message.id(), dl.id(), message.attempts(), reason);
    return dl;
  }

  private static String errorCode(FailedMessage failed) {
    Throwable t = failed.error();
    if (t == null) return "unknown";
    return t.getClass().getSimpleName();
  }
}
