package br.gov.sus.nexus.connectors.sdk.retry;

import java.util.List;
import org.jboss.logging.Logger;

/** DLQ apenas em log (último recurso). */
public class LogDeadLetterSink implements DeadLetterSink {

  private static final Logger LOG = Logger.getLogger(LogDeadLetterSink.class);

  @Override
  public void accept(DeadLetter dl) {
    LOG.errorf(
        "DEAD LETTER id=%s message_id=%s connector=%s stage=%s attempts=%d reason=%s raw=%s",
        dl.id(),
        dl.messageId(),
        dl.connectorId(),
        dl.stage(),
        dl.attempts(),
        dl.reason(),
        dl.payloadRef());
  }

  @Override
  public List<DeadLetter> open(int limit) {
    return List.of();
  }
}
