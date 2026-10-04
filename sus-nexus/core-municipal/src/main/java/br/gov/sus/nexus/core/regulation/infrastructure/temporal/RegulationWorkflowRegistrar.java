package br.gov.sus.nexus.core.regulation.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.temporal.WorkflowRegistrar;
import io.quarkus.arc.ClientProxy;
import io.temporal.worker.Worker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Registra {@code RegulationSlaWorkflow} e suas activities no worker. */
@ApplicationScoped
public class RegulationWorkflowRegistrar implements WorkflowRegistrar {

  @Inject RegulationSlaActivitiesImpl activities;

  @Override
  public void register(Worker worker) {
    worker.registerWorkflowImplementationTypes(RegulationSlaWorkflowImpl.class);
    worker.registerActivitiesImplementations(ClientProxy.unwrap(activities));
  }
}
