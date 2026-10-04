package br.gov.sus.nexus.core.hospital.infrastructure.temporal;

import br.gov.sus.nexus.core.hospital.api.HospitalService;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Activities executadas no worker: tenant do input aplicado via {@link TenantTransactions#runAs}.
 */
@ApplicationScoped
public class DischargeFollowUpActivitiesImpl implements DischargeFollowUpActivities {

  @Inject TenantTransactions transactions;
  @Inject HospitalService service;

  @Override
  public Snapshot snapshot(String tenantId, String episodeId) {
    return transactions.runAs(
        tenantId,
        () ->
            service
                .followupSnapshot(episodeId)
                .map(s -> new Snapshot(s.status(), s.contacted(), s.closed()))
                .orElse(new Snapshot("unknown", false, true)));
  }

  @Override
  public boolean escalate(String tenantId, String episodeId) {
    return transactions.runAs(tenantId, () -> service.escalateFollowup(episodeId));
  }

  @Override
  public boolean closeNotFound(String tenantId, String episodeId) {
    return transactions.runAs(tenantId, () -> service.closeFollowupNotFound(episodeId));
  }
}
