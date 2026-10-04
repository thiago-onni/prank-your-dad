package br.gov.sus.nexus.core.regulation.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import br.gov.sus.nexus.core.regulation.api.RegulationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Activities executadas no worker: tenant do input aplicado via {@link TenantTransactions#runAs}.
 */
@ApplicationScoped
public class RegulationSlaActivitiesImpl implements RegulationSlaActivities {

  @Inject TenantTransactions transactions;
  @Inject RegulationService service;

  @Override
  public boolean isAwaitingDecision(String tenantId, String requestId) {
    return transactions.runAs(
        tenantId,
        () -> {
          try {
            return !service.get(requestId).status().isDecided();
          } catch (RuntimeException e) {
            return false;
          }
        });
  }

  @Override
  public boolean halfSla(String tenantId, String requestId) {
    return transactions.runAs(tenantId, () -> service.halfSlaReached(requestId).isPresent());
  }

  @Override
  public boolean breachSla(String tenantId, String requestId) {
    return transactions.runAs(tenantId, () -> service.breachSla(requestId).isPresent());
  }
}
