package br.gov.sus.nexus.core.production.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.temporal.TemporalClientProvider;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Starter/sinalizador do {@code ProductionPreAuditWorkflow}: iniciado pelo consumidor de {@code
 * sus.production.record.created} ({@code REJECT_DUPLICATE} — replay seguro) e sinalizado ({@code
 * corrected}) a cada correção/reenvio. Sem cliente Temporal (dev/test) é no-op. O prazo da
 * competência é lido no início (reprodutibilidade).
 */
@ApplicationScoped
public class ProductionWorkflowStarter {

  private static final Logger LOG = Logger.getLogger(ProductionWorkflowStarter.class);

  @Inject TemporalClientProvider provider;
  @Inject TransactionSynchronizationRegistry registry;

  public boolean startPreAudit(String tenantId, String recordId, Instant deadlineAt) {
    Optional<WorkflowClient> client = provider.client();
    if (client.isEmpty()) {
      return false;
    }
    try {
      ProductionPreAuditWorkflow wf =
          client
              .get()
              .newWorkflowStub(
                  ProductionPreAuditWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(ProductionPreAuditWorkflow.WORKFLOW_ID_PREFIX + recordId)
                      .setTaskQueue(provider.taskQueue())
                      .setWorkflowIdReusePolicy(
                          WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                      .build());
      WorkflowClient.start(
          wf::run, new ProductionPreAuditWorkflow.Input(tenantId, recordId, deadlineAt.toString()));
      return true;
    } catch (WorkflowExecutionAlreadyStarted e) {
      LOG.debugf("workflow production-preaudit:%s já iniciado (replay)", recordId);
      return false;
    }
  }

  /**
   * Sinaliza {@code corrected} APÓS o commit da transação corrente (a activity de revalidação lê o
   * estado persistido); sem transação ativa, sinaliza na hora.
   */
  public void signalCorrectedAfterCommit(String recordId) {
    if (provider.client().isEmpty()) {
      return;
    }
    if (registry.getTransactionStatus() == Status.STATUS_ACTIVE) {
      registry.registerInterposedSynchronization(
          new Synchronization() {
            @Override
            public void beforeCompletion() {}

            @Override
            public void afterCompletion(int status) {
              if (status == Status.STATUS_COMMITTED) {
                signalCorrected(recordId);
              }
            }
          });
      return;
    }
    signalCorrected(recordId);
  }

  public void signalCorrected(String recordId) {
    provider
        .client()
        .ifPresent(
            c -> {
              try {
                c.newWorkflowStub(
                        ProductionPreAuditWorkflow.class,
                        ProductionPreAuditWorkflow.WORKFLOW_ID_PREFIX + recordId)
                    .corrected();
              } catch (WorkflowNotFoundException e) {
                LOG.debugf(
                    "workflow production-preaudit:%s inexistente/encerrado; sinal ignorado",
                    recordId);
              }
            });
  }
}
