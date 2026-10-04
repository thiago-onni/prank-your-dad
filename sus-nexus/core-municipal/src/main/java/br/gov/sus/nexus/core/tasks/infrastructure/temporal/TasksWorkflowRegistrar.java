package br.gov.sus.nexus.core.tasks.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.temporal.WorkflowRegistrar;
import io.quarkus.arc.ClientProxy;
import io.temporal.worker.Worker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Registra {@code TaskSlaWorkflow} e {@code MpiReviewWorkflow} (e suas activities) no worker. */
@ApplicationScoped
public class TasksWorkflowRegistrar implements WorkflowRegistrar {

  @Inject TaskSlaActivitiesImpl taskSlaActivities;
  @Inject MpiReviewActivitiesImpl mpiReviewActivities;

  @Override
  public void register(Worker worker) {
    worker.registerWorkflowImplementationTypes(
        TaskSlaWorkflowImpl.class, MpiReviewWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        ClientProxy.unwrap(taskSlaActivities), ClientProxy.unwrap(mpiReviewActivities));
  }
}
