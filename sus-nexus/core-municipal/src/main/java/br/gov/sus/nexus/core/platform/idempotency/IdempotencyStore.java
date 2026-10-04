package br.gov.sus.nexus.core.platform.idempotency;

import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Persistência de {@code platform.idempotency_key} (RLS por tenant; escritas em transação própria)
 * e expurgo agendado das chaves com mais de {@code sus.idempotency.ttl} (padrão 72 h).
 */
@ApplicationScoped
public class IdempotencyStore {

  private static final Logger LOG = Logger.getLogger(IdempotencyStore.class);

  /** Resposta armazenada. */
  public record Stored(String requestHash, int statusCode, String responseBody) {}

  @Inject EntityManager entityManager;
  @Inject TenantContext tenantContext;
  @Inject TenantTransactions transactions;

  @ConfigProperty(name = "sus.idempotency.ttl", defaultValue = "PT72H")
  java.time.Duration ttl;

  public Optional<Stored> find(String key) {
    return transactions.requiringNew(
        () -> {
          @SuppressWarnings("unchecked")
          List<Object[]> rows =
              entityManager
                  .createNativeQuery(
                      "select request_hash, response_status, response_body::text"
                          + " from platform.idempotency_key"
                          + " where tenant_id = ?1 and idempotency_key = ?2")
                  .setParameter(1, tenantContext.require())
                  .setParameter(2, key)
                  .getResultList();
          if (rows.isEmpty()) {
            return Optional.empty();
          }
          Object[] r = rows.get(0);
          return Optional.of(
              new Stored(
                  (String) r[0], ((Number) r[1]).intValue(), r[2] == null ? null : (String) r[2]));
        });
  }

  public void save(String key, String requestHash, int status, String body) {
    transactions.requiringNew(
        () ->
            entityManager
                .createNativeQuery(
                    "insert into platform.idempotency_key"
                        + " (tenant_id, idempotency_key, request_hash, response_status,"
                        + " response_body, created_at) values (?1, ?2, ?3, ?4, cast(?5 as jsonb),"
                        + " now()) on conflict (tenant_id, idempotency_key) do nothing")
                .setParameter(1, tenantContext.require())
                .setParameter(2, key)
                .setParameter(3, requestHash)
                .setParameter(4, status)
                .setParameter(5, body)
                .executeUpdate());
  }

  /** Expurgo das chaves expiradas (função SECURITY DEFINER; independe do tenant). */
  @Scheduled(every = "{sus.idempotency.purge-every}", identity = "idempotency-purge")
  public void purgeExpired() {
    long removed = purge();
    if (removed > 0) {
      LOG.infof("idempotency_key: %d chave(s) expirada(s) removida(s)", removed);
    }
  }

  public long purge() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ((Number)
                        entityManager
                            .createNativeQuery(
                                "select platform.purge_idempotency_keys(cast(?1 as interval))")
                            .setParameter(1, ttl.toSeconds() + " seconds")
                            .getSingleResult())
                    .longValue());
  }
}
