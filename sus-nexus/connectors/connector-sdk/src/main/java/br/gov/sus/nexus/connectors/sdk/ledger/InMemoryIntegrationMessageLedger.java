package br.gov.sus.nexus.connectors.sdk.ledger;

import br.gov.sus.nexus.connectors.sdk.api.Period;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Ledger em memória (dev/teste). */
public class InMemoryIntegrationMessageLedger implements IntegrationMessageLedger {

  private final Map<String, IntegrationMessage> store = new ConcurrentHashMap<>();

  @Override
  public IntegrationMessage save(IntegrationMessage message) {
    store.put(message.id(), message);
    return message;
  }

  @Override
  public Optional<IntegrationMessage> findById(String id) {
    return Optional.ofNullable(store.get(id));
  }

  @Override
  public List<IntegrationMessage> findByStatus(IntegrationMessageStatus status, int limit) {
    return store.values().stream().filter(m -> m.status() == status).limit(limit).toList();
  }

  @Override
  public long count(String entityType, IntegrationMessageStatus status, Period period) {
    return store.values().stream()
        .filter(m -> m.status() == status)
        .filter(m -> entityType == null || entityType.equals(m.entityType()))
        .filter(m -> period == null || period.contains(m.receivedAt()))
        .count();
  }

  public List<IntegrationMessage> all() {
    return List.copyOf(store.values());
  }

  public void clear() {
    store.clear();
  }
}
