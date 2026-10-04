package br.gov.sus.nexus.core.exams.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.temporal.TemporalClientProvider;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Starter/sinalizador do {@code ExamFollowUpWorkflow}, acionado pelos consumidores ({@code
 * REJECT_DUPLICATE} → replay seguro). Sem cliente Temporal (dev/test) é no-op. Prazos lidos da
 * configuração no início do workflow.
 */
@ApplicationScoped
public class ExamWorkflowStarter {

  private static final Logger LOG = Logger.getLogger(ExamWorkflowStarter.class);

  @Inject TemporalClientProvider provider;

  @ConfigProperty(name = "sus.exams.not-scheduled-days", defaultValue = "15")
  int notScheduledDays;

  @ConfigProperty(name = "sus.exams.result-pending-days", defaultValue = "7")
  int resultPendingDays;

  @ConfigProperty(name = "sus.exams.followup-days", defaultValue = "10")
  int followupDays;

  public boolean startExamFollowUp(String tenantId, String orderId, Instant requestedAt) {
    Optional<WorkflowClient> client = provider.client();
    if (client.isEmpty()) {
      return false;
    }
    try {
      ExamFollowUpWorkflow wf =
          client
              .get()
              .newWorkflowStub(
                  ExamFollowUpWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(ExamFollowUpWorkflow.WORKFLOW_ID_PREFIX + orderId)
                      .setTaskQueue(provider.taskQueue())
                      .setWorkflowIdReusePolicy(
                          WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                      .build());
      WorkflowClient.start(
          wf::run,
          new ExamFollowUpWorkflow.Input(
              tenantId,
              orderId,
              requestedAt.toString(),
              notScheduledDays,
              resultPendingDays,
              followupDays));
      return true;
    } catch (WorkflowExecutionAlreadyStarted e) {
      LOG.debugf("workflow exam-followup:%s já iniciado (replay)", orderId);
      return false;
    }
  }

  /** Mapeia o status do pedido para o sinal correspondente. */
  public void signalStatus(String orderId, String status) {
    switch (status) {
      case "scheduled" -> signal(orderId, ExamFollowUpWorkflow::scheduled);
      case "collected", "performed" -> signal(orderId, ExamFollowUpWorkflow::performed);
      case "reported" -> signal(orderId, ExamFollowUpWorkflow::reported);
      case "cancelled", "not_performed" -> signal(orderId, wf -> wf.closed(status));
      default -> {
        // requested/authorized: sem sinal
      }
    }
  }

  public void signalNoShow(String orderId) {
    signal(orderId, ExamFollowUpWorkflow::noShow);
  }

  public void signalFollowupCompleted(String orderId) {
    signal(orderId, ExamFollowUpWorkflow::followupCompleted);
  }

  private void signal(String orderId, Consumer<ExamFollowUpWorkflow> signal) {
    provider
        .client()
        .ifPresent(
            c -> {
              try {
                signal.accept(
                    c.newWorkflowStub(
                        ExamFollowUpWorkflow.class,
                        ExamFollowUpWorkflow.WORKFLOW_ID_PREFIX + orderId));
              } catch (WorkflowNotFoundException e) {
                LOG.debugf(
                    "workflow exam-followup:%s inexistente/encerrado; sinal ignorado", orderId);
              }
            });
  }
}
