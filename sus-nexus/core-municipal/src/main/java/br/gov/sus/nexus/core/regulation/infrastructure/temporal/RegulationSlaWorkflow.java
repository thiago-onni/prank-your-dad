package br.gov.sus.nexus.core.regulation.infrastructure.temporal;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * SLA de decisão regulatória (REG-010; plano §8.2): {@code workflowId = regulation-sla:<id>}. Timer
 * em 50% do prazo (pendência documental → tarefa {@code regulation_pending_document}) e em 100%
 * (pendência {@code sla_breached}, evento {@code status.changed} com {@code sla_breached=true} e
 * tarefa {@code generic} na fila {@code regulacao}). Sinais {@code status_changed} encerram quando
 * o status tem decisão/é terminal.
 */
@WorkflowInterface
public interface RegulationSlaWorkflow {

  String WORKFLOW_ID_PREFIX = "regulation-sla:";

  /** Entrada: prazos lidos no início (ISO-8601) para reprodutibilidade. */
  record Input(String tenantId, String requestId, String requestedAt, String slaDueAt) {}

  @WorkflowMethod
  String run(Input input);

  @SignalMethod
  void statusChanged(String status);
}
