package br.gov.sus.nexus.core.tasks.infrastructure.temporal;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Revisão de caso do MPI (plano §8.2): {@code workflowId = mpi-review:<case_id>}. Garante a tarefa
 * {@code mpi_review} na fila {@code cadastro_mestre}, aguarda a decisão (sinal {@code decided}) até
 * o SLA de revisão e, estourado, escalona a tarefa.
 */
@WorkflowInterface
public interface MpiReviewWorkflow {

  String WORKFLOW_ID_PREFIX = "mpi-review:";

  /** Entrada: SLA em segundos lido da política vigente no início. */
  record Input(String tenantId, String caseId, String citizenId, long reviewSlaSeconds) {}

  @WorkflowMethod
  String run(Input input);

  @SignalMethod
  void decided(String decision);
}
