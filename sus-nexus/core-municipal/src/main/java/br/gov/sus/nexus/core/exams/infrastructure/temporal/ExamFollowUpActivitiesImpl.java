package br.gov.sus.nexus.core.exams.infrastructure.temporal;

import br.gov.sus.nexus.core.exams.api.ExamOrderDto;
import br.gov.sus.nexus.core.exams.api.ExamOrderStatus;
import br.gov.sus.nexus.core.exams.api.ExamService;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Activities executadas no worker: tenant do input aplicado via {@link TenantTransactions#runAs}.
 */
@ApplicationScoped
public class ExamFollowUpActivitiesImpl implements ExamFollowUpActivities {

  @Inject TenantTransactions transactions;
  @Inject ExamService service;

  @Override
  public Snapshot snapshot(String tenantId, String orderId) {
    return transactions.runAs(
        tenantId,
        () -> {
          try {
            ExamOrderDto o = service.get(orderId);
            ExamOrderStatus s = o.status();
            boolean followupDone =
                o.cycleTimes() != null && o.cycleTimes().reportToFollowup() != null;
            return new Snapshot(
                s.wire(),
                o.scheduledAt() != null || s == ExamOrderStatus.SCHEDULED || s.isPerformed(),
                o.performedAt() != null || s.isPerformed(),
                s == ExamOrderStatus.REPORTED || (o.results() != null && !o.results().isEmpty()),
                s.isTerminal(),
                followupDone);
          } catch (RuntimeException e) {
            return new Snapshot("unknown", false, false, false, true, false);
          }
        });
  }

  @Override
  public boolean flagNotScheduled(String tenantId, String orderId) {
    return transactions.runAs(tenantId, () -> service.flagNotScheduled(orderId).isPresent());
  }

  @Override
  public boolean noShowRecovery(String tenantId, String orderId) {
    return transactions.runAs(tenantId, () -> service.openNoShowRecovery(orderId).isPresent());
  }

  @Override
  public boolean flagResultPending(String tenantId, String orderId) {
    return transactions.runAs(tenantId, () -> service.flagResultPending(orderId));
  }

  @Override
  public boolean ensureResultFollowup(String tenantId, String orderId) {
    return transactions.runAs(tenantId, () -> service.ensureResultFollowup(orderId).isPresent());
  }
}
