package br.gov.sus.nexus.core.tasks.infrastructure;

import br.gov.sus.nexus.core.tasks.domain.CareTask;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositório de tarefas (RLS garante o tenant). */
@ApplicationScoped
public class CareTaskRepository implements PanacheRepositoryBase<CareTask, String> {

  static final String OPEN_STATES = "('open','assigned','in_progress','escalated')";

  public long countOpen(String citizenId) {
    return count("citizenId = ?1 and status in " + OPEN_STATES, citizenId);
  }

  public Optional<CareTask> findOpenByOrigin(String originKind, String originId) {
    return find(
            "originKind = ?1 and originId = ?2 and status in " + OPEN_STATES + " order by id",
            originKind,
            originId)
        .firstResultOptional();
  }

  public List<CareTask> list(
      String status,
      String taskType,
      String assigneeKind,
      String assigneeId,
      String citizenId,
      Boolean overdue,
      String beforeId,
      int limitPlusOne) {
    StringBuilder q = new StringBuilder("1 = 1");
    List<Object> params = new ArrayList<>();
    if (status != null) {
      params.add(status);
      q.append(" and status = ?").append(params.size());
    }
    if (taskType != null) {
      params.add(taskType);
      q.append(" and taskType = ?").append(params.size());
    }
    if (assigneeKind != null) {
      params.add(assigneeKind);
      q.append(" and assigneeKind = ?").append(params.size());
    }
    if (assigneeId != null) {
      params.add(assigneeId);
      q.append(" and assigneeId = ?").append(params.size());
    }
    if (citizenId != null) {
      params.add(citizenId);
      q.append(" and citizenId = ?").append(params.size());
    }
    if (overdue != null) {
      params.add(Instant.now());
      if (overdue) {
        q.append(" and dueAt < ?").append(params.size()).append(" and status in " + OPEN_STATES);
      } else {
        q.append(" and (dueAt is null or dueAt >= ?").append(params.size()).append(")");
      }
    }
    if (beforeId != null) {
      params.add(beforeId);
      q.append(" and id < ?").append(params.size());
    }
    q.append(" order by id desc");
    return find(q.toString(), params.toArray()).page(0, limitPlusOne).list();
  }
}
