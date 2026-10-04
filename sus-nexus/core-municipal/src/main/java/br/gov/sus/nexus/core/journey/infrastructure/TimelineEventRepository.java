package br.gov.sus.nexus.core.journey.infrastructure;

import br.gov.sus.nexus.core.journey.domain.TimelineEvent;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositório do read model da timeline (RLS garante o tenant). */
@ApplicationScoped
public class TimelineEventRepository implements PanacheRepositoryBase<TimelineEvent, String> {

  public Optional<TimelineEvent> findByEventId(String eventId) {
    return find("eventId = ?1", eventId).firstResultOptional();
  }

  /** Keyset decrescente por (occurred_at, id). */
  public List<TimelineEvent> timeline(
      String citizenId,
      Instant from,
      Instant to,
      List<String> domains,
      String cnes,
      String status,
      Instant beforeAt,
      String beforeId,
      int limitPlusOne) {
    StringBuilder q = new StringBuilder("citizenId = ?1");
    List<Object> params = new ArrayList<>();
    params.add(citizenId);
    if (from != null) {
      params.add(from);
      q.append(" and occurredAt >= ?").append(params.size());
    }
    if (to != null) {
      params.add(to);
      q.append(" and occurredAt <= ?").append(params.size());
    }
    if (domains != null && !domains.isEmpty()) {
      params.add(domains);
      q.append(" and domain in ?").append(params.size());
    }
    if (cnes != null) {
      params.add(cnes);
      q.append(" and cnes = ?").append(params.size());
    }
    if (status != null) {
      params.add(status);
      q.append(" and status = ?").append(params.size());
    }
    if (beforeAt != null && beforeId != null) {
      params.add(beforeAt);
      int i = params.size();
      params.add(beforeId);
      int j = params.size();
      q.append(" and (occurredAt < ?")
          .append(i)
          .append(" or (occurredAt = ?")
          .append(i)
          .append(" and id < ?")
          .append(j)
          .append("))");
    }
    q.append(" order by occurredAt desc, id desc");
    return find(q.toString(), params.toArray()).page(0, limitPlusOne).list();
  }

  public List<String> careLines(String citizenId) {
    return getEntityManager()
        .createQuery(
            "select distinct t.careLine from TimelineEvent t where t.citizenId = ?1"
                + " and t.careLine is not null order by t.careLine",
            String.class)
        .setParameter(1, citizenId)
        .getResultList();
  }

  public Optional<Instant> lastOccurred(String citizenId, String domain) {
    return find(
            "citizenId = ?1 and domain = ?2 order by occurredAt desc, id desc", citizenId, domain)
        .firstResultOptional()
        .map(t -> t.occurredAt);
  }

  /** Merge: reatribui eventos dos cidadãos fundidos ao sobrevivente. */
  public int reassign(List<String> mergedIds, String survivingId) {
    if (mergedIds.isEmpty()) {
      return 0;
    }
    return update("citizenId = ?1 where citizenId in ?2", survivingId, mergedIds);
  }

  /** Unmerge: devolve os eventos ao cidadão de origem. */
  public int revert(List<String> mergedIds) {
    if (mergedIds.isEmpty()) {
      return 0;
    }
    return getEntityManager()
        .createQuery(
            "update TimelineEvent t set t.citizenId = t.originalCitizenId"
                + " where t.originalCitizenId in ?1")
        .setParameter(1, mergedIds)
        .executeUpdate();
  }
}
