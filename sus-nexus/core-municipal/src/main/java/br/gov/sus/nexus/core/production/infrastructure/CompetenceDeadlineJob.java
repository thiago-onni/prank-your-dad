package br.gov.sus.nexus.core.production.infrastructure;

import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import br.gov.sus.nexus.core.production.api.ProductionService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * {@code CompetenceDeadlineWorkflow} (plano §8.2, PRO-007) como job agendado: percorre os tenants
 * com produção em pré-auditoria ({@code production.active_tenants()}, SECURITY DEFINER) e emite os
 * alertas D-5/D-1 configurados em {@code production_deadline.alert_days} como tarefas {@code
 * production_issue} na fila {@code auditoria} (idempotente por {@code production_deadline_alert}).
 * {@link #runOnce(Instant)} é invocável em testes e operação.
 */
@ApplicationScoped
public class CompetenceDeadlineJob {

  private static final Logger LOG = Logger.getLogger(CompetenceDeadlineJob.class);

  @Inject EntityManager entityManager;
  @Inject TenantTransactions transactions;
  @Inject ProductionService service;

  @Scheduled(cron = "{sus.production.deadline-alert-cron}", identity = "competence-deadline")
  void scheduled() {
    try {
      runOnce(Instant.now());
    } catch (RuntimeException e) {
      LOG.warnf("alertas de prazo de competência falharam: %s", e.getClass().getSimpleName());
    }
  }

  /** Executa a varredura em todos os tenants; retorna quantos alertas foram emitidos. */
  public int runOnce(Instant now) {
    int total = 0;
    for (String tenant : tenants()) {
      total += transactions.runAs(tenant, () -> service.raiseDeadlineAlerts(now));
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
                        .createNativeQuery("select production.active_tenants()")
                        .getResultList());
  }
}
