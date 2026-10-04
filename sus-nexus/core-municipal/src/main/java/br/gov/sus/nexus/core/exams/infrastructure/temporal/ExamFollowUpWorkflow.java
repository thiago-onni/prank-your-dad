package br.gov.sus.nexus.core.exams.infrastructure.temporal;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow 1 da especificação (plano §8.2): {@code workflowId = exam-followup:<exam_order_id>}.
 * Aguarda agendamento por N dias (→ tarefa {@code exam_not_scheduled} para a UBS solicitante),
 * realização (falta → {@code no_show_recovery}), laudo (→ pendência {@code result_pending}) e
 * retorno em M dias (→ {@code exam_result_followup}). Encerra em cancelamento/não realização ou
 * retorno concluído. Prazos lidos no início (reprodutibilidade).
 */
@WorkflowInterface
public interface ExamFollowUpWorkflow {

  String WORKFLOW_ID_PREFIX = "exam-followup:";

  record Input(
      String tenantId,
      String orderId,
      String requestedAt,
      int notScheduledDays,
      int resultPendingDays,
      int followupDays) {}

  @WorkflowMethod
  String run(Input input);

  @SignalMethod
  void scheduled();

  @SignalMethod
  void performed();

  @SignalMethod
  void reported();

  @SignalMethod
  void noShow();

  @SignalMethod
  void followupCompleted();

  @SignalMethod
  void closed(String status);
}
