package br.gov.sus.nexus.core.platform.events;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Idempotência de consumidores: {@code platform.event_inbox(event_id, consumer_group,
 * processed_at)}. Deve ser usado dentro da mesma transação do processamento do evento.
 */
@ApplicationScoped
public class EventInbox {

  private static final String INSERT_SQL =
      "insert into platform.event_inbox (event_id, consumer_group, processed_at)"
          + " values (?1, ?2, now()) on conflict (event_id, consumer_group) do nothing";
  private static final String EXISTS_SQL =
      "select count(*) from platform.event_inbox where event_id = ?1 and consumer_group = ?2";

  @Inject EntityManager entityManager;

  /** Marca o evento como processado; retorna {@code false} se já havia sido processado. */
  public boolean markProcessed(String eventId, String consumerGroup) {
    int rows =
        entityManager
            .createNativeQuery(INSERT_SQL)
            .setParameter(1, eventId)
            .setParameter(2, consumerGroup)
            .executeUpdate();
    return rows > 0;
  }

  public boolean isProcessed(String eventId, String consumerGroup) {
    Number n =
        (Number)
            entityManager
                .createNativeQuery(EXISTS_SQL)
                .setParameter(1, eventId)
                .setParameter(2, consumerGroup)
                .getSingleResult();
    return n.longValue() > 0;
  }

  /**
   * Executa {@code work} exatamente uma vez por (evento, grupo). Retorna vazio quando o evento já
   * tinha sido processado (duplicata/replay).
   */
  public <T> Optional<T> once(String eventId, String consumerGroup, Supplier<T> work) {
    if (!markProcessed(eventId, consumerGroup)) {
      return Optional.empty();
    }
    return Optional.ofNullable(work.get());
  }
}
