package br.gov.sus.nexus.core.tasks.infrastructure.temporal;

import io.temporal.activity.ActivityInterface;

/** Activities (idempotentes) do {@link TaskSlaWorkflow}. */
@ActivityInterface
public interface TaskSlaActivities {

  boolean isOpen(String tenantId, String taskId);

  /** Marca o estouro de SLA e escalona; retorna {@code true} se a tarefa foi marcada. */
  boolean breachSla(String tenantId, String taskId);
}
