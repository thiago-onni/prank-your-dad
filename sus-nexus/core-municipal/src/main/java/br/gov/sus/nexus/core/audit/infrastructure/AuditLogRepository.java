package br.gov.sus.nexus.core.audit.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Acesso SQL nativo ao {@code audit.audit_log} (append-only). */
@ApplicationScoped
public class AuditLogRepository {

  private static final String LOCK_SQL = "select pg_advisory_xact_lock(hashtext(?1))";
  private static final String LAST_HASH_SQL =
      "select hash from audit.audit_log where tenant_id = ?1 order by seq desc limit 1";
  private static final String INSERT_SQL =
      "insert into audit.audit_log (id, tenant_id, occurred_at, actor_id, actor_roles, action,"
          + " resource_type, resource_id, citizen_id, reason, details, correlation_id, prev_hash,"
          + " hash) values (?1, ?2, ?3, ?4, string_to_array(?5, ','), ?6, ?7, ?8, ?9, ?10,"
          + " cast(?11 as jsonb), ?12, ?13, ?14)";

  @Inject EntityManager entityManager;

  /** Serializa escritas do tenant para manter o encadeamento consistente. */
  public void lockTenantChain(String tenantId) {
    entityManager.createNativeQuery(LOCK_SQL).setParameter(1, tenantId).getSingleResult();
  }

  public Optional<String> lastHash(String tenantId) {
    @SuppressWarnings("unchecked")
    List<Object> rows =
        entityManager.createNativeQuery(LAST_HASH_SQL).setParameter(1, tenantId).getResultList();
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0).toString());
  }

  public void insert(
      String id,
      String tenantId,
      OffsetDateTime occurredAt,
      String actorId,
      String rolesCsv,
      String action,
      String resourceType,
      String resourceId,
      String citizenId,
      String reason,
      String detailsJson,
      String correlationId,
      String prevHash,
      String hash) {
    entityManager
        .createNativeQuery(INSERT_SQL)
        .setParameter(1, id)
        .setParameter(2, tenantId)
        .setParameter(3, occurredAt)
        .setParameter(4, actorId)
        .setParameter(5, rolesCsv)
        .setParameter(6, action)
        .setParameter(7, resourceType)
        .setParameter(8, resourceId)
        .setParameter(9, citizenId)
        .setParameter(10, reason)
        .setParameter(11, detailsJson)
        .setParameter(12, correlationId)
        .setParameter(13, prevHash)
        .setParameter(14, hash)
        .executeUpdate();
  }
}
