package br.gov.sus.nexus.core.production.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import br.gov.sus.nexus.core.production.api.ProductionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Activities executadas no worker: tenant do input aplicado via {@link TenantTransactions}. */
@ApplicationScoped
public class ProductionPreAuditActivitiesImpl implements ProductionPreAuditActivities {

  @Inject TenantTransactions transactions;
  @Inject ProductionService service;

  @Override
  public String validate(String tenantId, String recordId) {
    return transactions.runAs(tenantId, () -> service.revalidate(recordId));
  }

  @Override
  public String expire(String tenantId, String recordId) {
    return transactions.runAs(tenantId, () -> service.expire(recordId));
  }
}
