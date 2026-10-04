package br.gov.sus.nexus.core.tasks.infrastructure.temporal;

import io.temporal.activity.ActivityInterface;

/** Activities (idempotentes) do {@link MpiReviewWorkflow}. */
@ActivityInterface
public interface MpiReviewActivities {

  /** Garante a tarefa {@code mpi_review} (origem {@code rule}/{@code case_id}); retorna o id. */
  String ensureReviewTask(String tenantId, String caseId, String citizenId);

  /** Estoura o SLA da tarefa de revisão (escalona); {@code true} se houve estouro. */
  boolean escalateReview(String tenantId, String caseId);

  /** Conclui a tarefa de revisão com o desfecho da decisão (idempotente). */
  boolean closeReviewTask(String tenantId, String caseId, String decision);
}
