package br.gov.sus.nexus.core.tasks.api;

import java.util.Optional;

/**
 * API pública de comandos do módulo tasks, usada por outros módulos (scheduling, identity via
 * eventos, workflows Temporal). Toda operação publica evento {@code sus.task.*} via outbox.
 */
public interface TaskCommands {

  /**
   * Cria a tarefa. {@code causationId} (opcional) é o event_id que originou a tarefa (ex.: {@code
   * sus.schedule.appointment.no_show}) — vai em {@code trace.causation_id} do evento {@code
   * sus.task.created}.
   */
  TaskDto create(TaskCreate create, String causationId);

  TaskDto transition(String taskId, TaskTransition transition);

  /**
   * Marca o estouro de SLA (idempotente): se a tarefa ainda está aberta e ainda não estourou,
   * publica {@code sla_breached} e escalona conforme {@code sla_policy}. Retorna a tarefa, ou vazio
   * se já encerrada.
   */
  Optional<TaskDto> breachSla(String taskId);

  /** Conclui (se aberta) a tarefa aberta de uma origem, ex.: caso de fusão decidido. */
  Optional<TaskDto> completeByOrigin(
      String originKind, String originId, String outcome, String reason);
}
