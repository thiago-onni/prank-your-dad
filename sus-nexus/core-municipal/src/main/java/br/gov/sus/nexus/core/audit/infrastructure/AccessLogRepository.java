package br.gov.sus.nexus.core.audit.infrastructure;

import br.gov.sus.nexus.core.audit.api.AccessLogEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Acesso SQL nativo ao {@code audit.access_log}. */
@ApplicationScoped
public class AccessLogRepository {

  private static final String INSERT_SQL =
      "insert into audit.access_log (id, tenant_id, actor_id, actor_roles, action, resource_type,"
          + " resource_id, citizen_id, purpose, decision, break_glass, justification,"
          + " correlation_id, occurred_at) values (?1, ?2, ?3, string_to_array(?4, ','), ?5, ?6,"
          + " ?7, ?8, ?9, ?10, ?11, ?12, ?13, now())";

  @Inject EntityManager entityManager;

  public void insert(
      String id,
      String tenantId,
      String actorId,
      String rolesCsv,
      String action,
      String resourceType,
      String resourceId,
      String citizenId,
      String purpose,
      String decision,
      boolean breakGlass,
      String justification,
      String correlationId) {
    entityManager
        .createNativeQuery(INSERT_SQL)
        .setParameter(1, id)
        .setParameter(2, tenantId)
        .setParameter(3, actorId)
        .setParameter(4, rolesCsv)
        .setParameter(5, action)
        .setParameter(6, resourceType)
        .setParameter(7, resourceId)
        .setParameter(8, citizenId)
        .setParameter(9, purpose)
        .setParameter(10, decision)
        .setParameter(11, breakGlass)
        .setParameter(12, justification)
        .setParameter(13, correlationId)
        .executeUpdate();
  }

  /** Lista ordenada por id desc (ULID ~ tempo), keyset {@code id < cursor}. */
  public List<AccessLogEntry> list(
      String citizenId,
      String actorId,
      OffsetDateTime from,
      OffsetDateTime to,
      String afterId,
      int limitPlusOne) {
    StringBuilder sql =
        new StringBuilder(
            "select id, actor_id, array_to_string(actor_roles, ','), action, resource_type,"
                + " resource_id, citizen_id, purpose, decision, break_glass, correlation_id,"
                + " occurred_at from audit.access_log where 1=1");
    List<Object> params = new ArrayList<>();
    if (citizenId != null) {
      params.add(citizenId);
      sql.append(" and citizen_id = ?").append(params.size());
    }
    if (actorId != null) {
      params.add(actorId);
      sql.append(" and actor_id = ?").append(params.size());
    }
    if (from != null) {
      params.add(from);
      sql.append(" and occurred_at >= ?").append(params.size());
    }
    if (to != null) {
      params.add(to);
      sql.append(" and occurred_at <= ?").append(params.size());
    }
    if (afterId != null) {
      params.add(afterId);
      sql.append(" and id < ?").append(params.size());
    }
    sql.append(" order by id desc limit ").append(limitPlusOne);

    Query query = entityManager.createNativeQuery(sql.toString());
    for (int i = 0; i < params.size(); i++) {
      query.setParameter(i + 1, params.get(i));
    }
    @SuppressWarnings("unchecked")
    List<Object[]> rows = query.getResultList();
    return rows.stream().map(AccessLogRepository::toEntry).toList();
  }

  private static AccessLogEntry toEntry(Object[] r) {
    String roles = r[2] == null ? "" : r[2].toString();
    return new AccessLogEntry(
        (String) r[0],
        (String) r[1],
        roles.isEmpty() ? List.of() : Arrays.asList(roles.split(",")),
        (String) r[3],
        (String) r[4],
        (String) r[5],
        (String) r[6],
        (String) r[7],
        (String) r[8],
        Boolean.TRUE.equals(r[9]),
        (String) r[10],
        toOffset(r[11]));
  }

  static OffsetDateTime toOffset(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof OffsetDateTime odt) {
      return odt;
    }
    if (value instanceof Timestamp ts) {
      return ts.toInstant().atOffset(ZoneOffset.UTC);
    }
    if (value instanceof java.time.Instant i) {
      return i.atOffset(ZoneOffset.UTC);
    }
    return OffsetDateTime.parse(value.toString());
  }
}
