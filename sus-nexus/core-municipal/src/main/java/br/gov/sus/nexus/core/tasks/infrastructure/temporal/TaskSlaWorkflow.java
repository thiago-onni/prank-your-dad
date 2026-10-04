package br.gov.sus.nexus.core.tasks.infrastructure.temporal;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * SLA de tarefa (plano §8.2): iniciado em {@code sus.task.created} com {@code workflowId =
 * task-sla:<task_id>}; timer até {@code due_at}; se a tarefa não foi encerrada, publica {@code
 * sla_breached} e escalona conforme {@code sla_policy}. Sinais {@code completed}/{@code cancelled}
 * encerram.
 */
@WorkflowInterface
public interface TaskSlaWorkflow {

  String WORKFLOW_ID_PREFIX = "task-sla:";

  /** Entrada do workflow (prazos lidos da política vigente no início — reprodutibilidade). */
  record Input(String tenantId, String taskId, String dueAt, String slaPolicyId) {}

  @WorkflowMethod
  String run(Input input);

  @SignalMethod
  void completed();

  @SignalMethod
  void cancelled();
}
