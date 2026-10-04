package br.gov.sus.nexus.core.hospital.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.temporal.TemporalClientProvider;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import br.gov.sus.nexus.core.tasks.api.SlaPolicyDto;
import br.gov.sus.nexus.core.tasks.api.TaskPriority;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import br.gov.sus.nexus.core.tasks.api.TaskType;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Starter/sinalizador do {@code DischargeFollowUpWorkflow}, acionado pelo consumidor de {@code
 * sus.hospital.discharge.completed} ({@code REJECT_DUPLICATE} → replay seguro) e sinalizado pelo
 * desfecho do contato. Sem cliente Temporal (dev/test) é no-op. O segundo prazo é o {@code
 * escalate_after} da {@code sla_policy} do risco, lido no início.
 */
@ApplicationScoped
public class HospitalWorkflowStarter {

  private static final Logger LOG = Logger.getLogger(HospitalWorkflowStarter.class);
  static final Duration DEFAULT_SECOND_DEADLINE = Duration.ofDays(3);

  @Inject TemporalClientProvider provider;
  @Inject TaskQueries taskQueries;
  @Inject TenantTransactions transactions;

  public boolean startDischargeFollowUp(
      String tenantId, String episodeId, Instant dueAt, String riskLevel) {
    Optional<WorkflowClient> client = provider.client();
    if (client.isEmpty()) {
      return false;
    }
    Duration second =
        transactions.runAs(
            tenantId,
            () ->
                taskQueries
                    .slaPolicy(TaskType.POST_DISCHARGE_FOLLOWUP, priorityFor(riskLevel))
                    .map(SlaPolicyDto::escalateAfter)
                    .filter(d -> d != null && !d.isZero() && !d.isNegative())
                    .orElse(DEFAULT_SECOND_DEADLINE));
    try {
      DischargeFollowUpWorkflow wf =
          client
              .get()
              .newWorkflowStub(
                  DischargeFollowUpWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(DischargeFollowUpWorkflow.WORKFLOW_ID_PREFIX + episodeId)
                      .setTaskQueue(provider.taskQueue())
                      .setWorkflowIdReusePolicy(
                          WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                      .build());
      WorkflowClient.start(
          wf::run,
          new DischargeFollowUpWorkflow.Input(
              tenantId, episodeId, dueAt.toString(), second.toSeconds(), riskLevel));
      return true;
    } catch (WorkflowExecutionAlreadyStarted e) {
      LOG.debugf("workflow discharge-followup:%s já iniciado (replay)", episodeId);
      return false;
    }
  }

  public void signalContact(String episodeId, String outcome) {
    provider
        .client()
        .ifPresent(
            c -> {
              try {
                c.newWorkflowStub(
                        DischargeFollowUpWorkflow.class,
                        DischargeFollowUpWorkflow.WORKFLOW_ID_PREFIX + episodeId)
                    .contacted(outcome);
              } catch (WorkflowNotFoundException e) {
                LOG.debugf(
                    "workflow discharge-followup:%s inexistente/encerrado; sinal ignorado",
                    episodeId);
              }
            });
  }

  static TaskPriority priorityFor(String risk) {
    return switch (risk == null ? "" : risk) {
      case "high" -> TaskPriority.HIGH;
      case "low" -> TaskPriority.LOW;
      default -> TaskPriority.MEDIUM;
    };
  }
}
