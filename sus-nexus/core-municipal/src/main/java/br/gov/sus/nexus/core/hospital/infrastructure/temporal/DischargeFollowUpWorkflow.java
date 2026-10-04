package br.gov.sus.nexus.core.hospital.infrastructure.temporal;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow 2 da especificação (plano §8.2): {@code workflowId = discharge-followup:<hep>}. Aguarda
 * o sinal de contato até o prazo da tarefa {@code post_discharge_followup} (SLA por risco); sem
 * contato → escalona (tarefa {@code active_search} para a microárea + lacuna {@code
 * post_discharge_no_contact}); vencido o segundo prazo ({@code escalate_after} da política) →
 * encerra como {@code not_found}. Prazos lidos no início (reprodutibilidade).
 */
@WorkflowInterface
public interface DischargeFollowUpWorkflow {

  String WORKFLOW_ID_PREFIX = "discharge-followup:";

  record Input(
      String tenantId,
      String episodeId,
      String dueAt,
      long secondDeadlineSeconds,
      String riskLevel) {}

  @WorkflowMethod
  String run(Input input);

  /** Contato efetivo ou desfecho final registrado ({@code POST /followup}). */
  @SignalMethod
  void contacted(String outcome);
}
