package br.gov.sus.nexus.core.exams.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.temporal.WorkflowRegistrar;
import io.quarkus.arc.ClientProxy;
import io.temporal.worker.Worker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Registra {@code ExamFollowUpWorkflow} e suas activities no worker. */
@ApplicationScoped
public class ExamWorkflowRegistrar implements WorkflowRegistrar {

  @Inject ExamFollowUpActivitiesImpl activities;

  @Override
  public void register(Worker worker) {
    worker.registerWorkflowImplementationTypes(ExamFollowUpWorkflowImpl.class);
    worker.registerActivitiesImplementations(ClientProxy.unwrap(activities));
  }
}
