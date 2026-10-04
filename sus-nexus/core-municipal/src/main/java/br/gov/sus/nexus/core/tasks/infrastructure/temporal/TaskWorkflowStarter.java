package br.gov.sus.nexus.core.tasks.infrastructure.temporal;

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
 * Starter dos workflows do módulo tasks. Acionado pelos consumidores Kafka ({@code sus.task.v1},
 * {@code sus.identity.merge.v1}) — não pelos serviços — para funcionar em replay: o {@code
 * workflowId} derivado do id de negócio impede duplicação. Sem cliente Temporal (dev/test) é no-op.
 */
@ApplicationScoped
public class TaskWorkflowStarter {

  private static final Logger LOG = Logger.getLogger(TaskWorkflowStarter.class);

  @Inject TemporalClientProvider provider;

  public boolean startTaskSla(String tenantId, String taskId, Instant dueAt, String slaPolicyId) {
    Optional<WorkflowClient> client = provider.client();
    if (client.isEmpty() || dueAt == null) {
      return false;
    }
    try {
      TaskSlaWorkflow wf =
          client
              .get()
              .newWorkflowStub(
                  TaskSlaWorkflow.class, options(TaskSlaWorkflow.WORKFLOW_ID_PREFIX + taskId));
      WorkflowClient.start(
          wf::run, new TaskSlaWorkflow.Input(tenantId, taskId, dueAt.toString(), slaPolicyId));
      return true;
    } catch (WorkflowExecutionAlreadyStarted e) {
      LOG.debugf("workflow task-sla:%s já iniciado (replay)", taskId);
      return false;
    }
  }

  public void signalTask(String taskId, String action) {
    provider
        .client()
        .ifPresent(
            c -> {
              try {
                TaskSlaWorkflow wf =
                    c.newWorkflowStub(
                        TaskSlaWorkflow.class, TaskSlaWorkflow.WORKFLOW_ID_PREFIX + taskId);
                if ("cancelled".equals(action)) {
                  wf.cancelled();
                } else {
                  wf.completed();
                }
              } catch (WorkflowNotFoundException e) {
                LOG.debugf("workflow task-sla:%s inexistente/encerrado; sinal ignorado", taskId);
              }
            });
  }

  public boolean startMpiReview(
      String tenantId, String caseId, String citizenId, Duration reviewSla) {
    Optional<WorkflowClient> client = provider.client();
    if (client.isEmpty()) {
      return false;
    }
    try {
      MpiReviewWorkflow wf =
          client
              .get()
              .newWorkflowStub(
                  MpiReviewWorkflow.class, options(MpiReviewWorkflow.WORKFLOW_ID_PREFIX + caseId));
      WorkflowClient.start(
          wf::run, new MpiReviewWorkflow.Input(tenantId, caseId, citizenId, reviewSla.toSeconds()));
      return true;
    } catch (WorkflowExecutionAlreadyStarted e) {
      LOG.debugf("workflow mpi-review:%s já iniciado (replay)", caseId);
      return false;
    }
  }

  public void signalMpiDecided(String caseId, String decision) {
    provider
        .client()
        .ifPresent(
            c -> {
              try {
                c.newWorkflowStub(
                        MpiReviewWorkflow.class, MpiReviewWorkflow.WORKFLOW_ID_PREFIX + caseId)
                    .decided(decision);
              } catch (WorkflowNotFoundException e) {
                LOG.debugf("workflow mpi-review:%s inexistente/encerrado; sinal ignorado", caseId);
              }
            });
  }

  private WorkflowOptions options(String workflowId) {
    return WorkflowOptions.newBuilder()
        .setWorkflowId(workflowId)
        .setTaskQueue(provider.taskQueue())
        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
        .build();
  }
}
