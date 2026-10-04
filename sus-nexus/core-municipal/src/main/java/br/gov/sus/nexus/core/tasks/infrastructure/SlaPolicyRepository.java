package br.gov.sus.nexus.core.tasks.infrastructure;

import br.gov.sus.nexus.core.tasks.api.Assignee;
import br.gov.sus.nexus.core.tasks.api.SlaPolicyDto;
import br.gov.sus.nexus.core.tasks.api.TaskPriority;
import br.gov.sus.nexus.core.tasks.api.TaskType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Políticas de SLA ({@code tasks.sla_policy}): a RLS expõe as globais ({@code tenant_id IS NULL}) e
 * as do tenant; a do tenant e a mais específica (prioridade informada) têm precedência.
 */
@ApplicationScoped
public class SlaPolicyRepository {

  private static final String SQL =
      "select id, task_type, priority, extract(epoch from due_in)::bigint,"
          + " extract(epoch from escalate_after)::bigint, escalate_to_kind, escalate_to_id,"
          + " policy_version from tasks.sla_policy where active and task_type = ?1"
          + " and (priority = ?2 or priority is null)"
          + " and effective_from <= now() and (effective_to is null or effective_to > now())"
          + " order by (tenant_id is null), (priority is null), effective_from desc limit 1";

  private static final String BY_ID =
      "select id, task_type, priority, extract(epoch from due_in)::bigint,"
          + " extract(epoch from escalate_after)::bigint, escalate_to_kind, escalate_to_id,"
          + " policy_version from tasks.sla_policy where id = ?1 and active";

  @Inject EntityManager entityManager;

  public Optional<SlaPolicyDto> resolve(TaskType taskType, TaskPriority priority) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(SQL)
            .setParameter(1, taskType.wire())
            .setParameter(2, priority == null ? "" : priority.wire())
            .getResultList();
    return rows.isEmpty() ? Optional.empty() : Optional.of(map(rows.get(0)));
  }

  public Optional<SlaPolicyDto> findById(String id) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager.createNativeQuery(BY_ID).setParameter(1, id).getResultList();
    return rows.isEmpty() ? Optional.empty() : Optional.of(map(rows.get(0)));
  }

  private static SlaPolicyDto map(Object[] r) {
    return new SlaPolicyDto(
        (String) r[0],
        TaskType.fromWire((String) r[1]),
        r[2] == null ? null : TaskPriority.fromWire((String) r[2]),
        Duration.ofSeconds(((Number) r[3]).longValue()),
        r[4] == null ? null : Duration.ofSeconds(((Number) r[4]).longValue()),
        r[5] == null ? null : new Assignee((String) r[5], (String) r[6]),
        (String) r[7]);
  }
}
