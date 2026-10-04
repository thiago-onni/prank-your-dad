package br.gov.sus.nexus.core.regulation.infrastructure.temporal;

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
import org.jboss.logging.Logger;

/**
 * Starter do {@code RegulationSlaWorkflow}, acionado pelo consumidor de {@code
 * sus.regulation.request.v1} (replay seguro: {@code REJECT_DUPLICATE}). Sem cliente Temporal
 * (dev/test) é no-op.
 */
@ApplicationScoped
public class RegulationWorkflowStarter {

  private static final Logger LOG = Logger.getLogger(RegulationWorkflowStarter.class);

  @Inject TemporalClientProvider provider;

  public boolean startRegulationSla(
      String tenantId, String requestId, Instant requestedAt, Instant slaDueAt) {
    Optional<WorkflowClient> client = provider.client();
    if (client.isEmpty() || slaDueAt == null) {
      return false;
    }
    try {
      RegulationSlaWorkflow wf =
          client
              .get()
              .newWorkflowStub(
                  RegulationSlaWorkflow.class,
                  WorkflowOptions.newBuilder()
                      .setWorkflowId(RegulationSlaWorkflow.WORKFLOW_ID_PREFIX + requestId)
                      .setTaskQueue(provider.taskQueue())
                      .setWorkflowIdReusePolicy(
                          WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                      .build());
      WorkflowClient.start(
          wf::run,
          new RegulationSlaWorkflow.Input(
              tenantId, requestId, requestedAt.toString(), slaDueAt.toString()));
      return true;
    } catch (WorkflowExecutionAlreadyStarted e) {
      LOG.debugf("workflow regulation-sla:%s já iniciado (replay)", requestId);
      return false;
    }
  }

  public void signalRegulationStatus(String requestId, String status) {
    provider
        .client()
        .ifPresent(
            c -> {
              try {
                c.newWorkflowStub(
                        RegulationSlaWorkflow.class,
                        RegulationSlaWorkflow.WORKFLOW_ID_PREFIX + requestId)
                    .statusChanged(status);
              } catch (WorkflowNotFoundException e) {
                LOG.debugf(
                    "workflow regulation-sla:%s inexistente/encerrado; sinal ignorado", requestId);
              }
            });
  }
}
