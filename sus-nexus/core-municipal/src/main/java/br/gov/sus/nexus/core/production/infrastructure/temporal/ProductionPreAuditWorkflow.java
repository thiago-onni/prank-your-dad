package br.gov.sus.nexus.core.production.infrastructure.temporal;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Workflow 3 da especificação (plano §8.2): {@code workflowId = production-preaudit:<prod_id>},
 * iniciado por {@code sus.production.record.created}. Valida (regras versionadas); se pendente,
 * aguarda o sinal {@code corrected} (correção humana ou reenvio da origem → revalida) até o prazo
 * da competência; vencido o prazo com o registro ainda pendente → pendência {@code
 * deadline_missed}. O lote continua exigindo aprovação humana (fora do workflow).
 */
@WorkflowInterface
public interface ProductionPreAuditWorkflow {

  String WORKFLOW_ID_PREFIX = "production-preaudit:";

  record Input(String tenantId, String recordId, String deadlineAt) {}

  @WorkflowMethod
  String run(Input input);

  @SignalMethod
  void corrected();
}
