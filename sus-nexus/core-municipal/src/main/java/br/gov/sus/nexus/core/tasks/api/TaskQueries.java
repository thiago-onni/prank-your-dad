package br.gov.sus.nexus.core.tasks.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.util.Optional;

/** API pública de consulta do módulo tasks. */
public interface TaskQueries {

  TaskDto get(String taskId);

  Page<TaskDto> list(
      TaskStatus status,
      TaskType taskType,
      String assigneeKind,
      String assigneeId,
      String citizenId,
      Boolean overdue,
      String cursor,
      Integer limit);

  /** Tarefas não encerradas do cidadão (JOR-008). */
  long countOpen(String citizenId);

  /** Tarefa aberta de uma origem (ex.: {@code rule}/{@code case_...}). */
  Optional<TaskDto> findOpenByOrigin(String originKind, String originId);

  /** Política de SLA vigente para o tipo/prioridade (tenant sobrepõe global). */
  Optional<SlaPolicyDto> slaPolicy(TaskType taskType, TaskPriority priority);
}
