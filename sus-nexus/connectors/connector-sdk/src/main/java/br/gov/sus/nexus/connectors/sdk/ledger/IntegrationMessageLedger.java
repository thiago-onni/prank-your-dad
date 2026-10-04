package br.gov.sus.nexus.connectors.sdk.ledger;

import br.gov.sus.nexus.connectors.sdk.api.Period;
import java.util.List;
import java.util.Optional;

/**
 * Ledger de {@code integration_message}; fonte dos dados de {@code /api/v1/integration/messages}.
 */
public interface IntegrationMessageLedger {

  IntegrationMessage save(IntegrationMessage message);

  Optional<IntegrationMessage> findById(String id);

  List<IntegrationMessage> findByStatus(IntegrationMessageStatus status, int limit);

  /** Conta mensagens no status informado para a entidade, por {@code received_at}. */
  long count(String entityType, IntegrationMessageStatus status, Period period);

  /** Mensagens publicadas (published ou processed) por entidade no período. */
  default long countPublished(String entityType, Period period) {
    return count(entityType, IntegrationMessageStatus.PUBLISHED, period)
        + count(entityType, IntegrationMessageStatus.PROCESSED, period);
  }
}
