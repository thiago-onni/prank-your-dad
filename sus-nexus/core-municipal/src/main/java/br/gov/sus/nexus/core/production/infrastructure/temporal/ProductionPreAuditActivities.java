package br.gov.sus.nexus.core.production.infrastructure.temporal;

import io.temporal.activity.ActivityInterface;

/** Activities (idempotentes) do {@link ProductionPreAuditWorkflow}. */
@ActivityInterface(namePrefix = "Production")
public interface ProductionPreAuditActivities {

  /** Revalida (se ainda em pré-auditoria) e devolve o status do registro. */
  String validate(String tenantId, String recordId);

  /** Prazo vencido: pendência {@code deadline_missed} se ainda pendente; devolve o status. */
  String expire(String tenantId, String recordId);
}
