package br.gov.sus.nexus.core.exams.infrastructure.temporal;

import io.temporal.activity.ActivityInterface;

/** Activities (idempotentes) do {@link ExamFollowUpWorkflow}. */
@ActivityInterface(namePrefix = "Exam")
public interface ExamFollowUpActivities {

  /** Estado persistido do pedido (reconciliação). */
  record Snapshot(
      String status,
      boolean scheduled,
      boolean performed,
      boolean reported,
      boolean terminal,
      boolean followupDone) {}

  Snapshot snapshot(String tenantId, String orderId);

  boolean flagNotScheduled(String tenantId, String orderId);

  boolean noShowRecovery(String tenantId, String orderId);

  boolean flagResultPending(String tenantId, String orderId);

  boolean ensureResultFollowup(String tenantId, String orderId);
}
