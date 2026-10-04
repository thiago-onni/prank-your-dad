package br.gov.sus.nexus.core.careplan.infrastructure;

import br.gov.sus.nexus.core.careplan.api.CarePlanService;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * {@code CareGapDetectionWorkflow} (plano §8.2) como job agendado diário: percorre os tenants com
 * planos ativos ({@code careplan.active_tenants()}, SECURITY DEFINER — o job roda sem tenant) e
 * executa {@link CarePlanService#detectGaps()} em cada um. {@link #runOnce()} é invocável em testes
 * e operações.
 */
@ApplicationScoped
public class CareGapDetectionJob {

  private static final Logger LOG = Logger.getLogger(CareGapDetectionJob.class);

  @Inject EntityManager entityManager;
  @Inject TenantTransactions transactions;
  @Inject CarePlanService service;

  @Scheduled(cron = "{sus.careplan.gap-detection-cron}", identity = "care-gap-detection")
  void scheduled() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      LOG.warnf("detecção de lacunas falhou: %s", e.getClass().getSimpleName());
    }
  }

  /** Executa a varredura em todos os tenants com planos ativos; retorna lacunas abertas. */
  public int runOnce() {
    int total = 0;
    for (String tenant : tenants()) {
      total += transactions.runAs(tenant, () -> service.detectGaps());
    }
    return total;
  }

  @SuppressWarnings("unchecked")
  private List<String> tenants() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                (List<String>)
                    entityManager
                        .createNativeQuery("select careplan.active_tenants()")
                        .getResultList());
  }
}
