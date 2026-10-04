package br.gov.sus.nexus.core.regulation.infrastructure.temporal;

import io.temporal.activity.ActivityInterface;

/** Activities (idempotentes) do {@link RegulationSlaWorkflow}. */
@ActivityInterface(namePrefix = "Regulation")
public interface RegulationSlaActivities {

  /** O pedido ainda aguarda decisão do regulador? */
  boolean isAwaitingDecision(String tenantId, String requestId);

  /** Metade do SLA: pendência documental aberta → tarefa; retorna {@code true} se criou/achou. */
  boolean halfSla(String tenantId, String requestId);

  /** Estouro do SLA de decisão; retorna {@code true} se o pedido foi marcado. */
  boolean breachSla(String tenantId, String requestId);
}
