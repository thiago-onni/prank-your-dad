package br.gov.sus.nexus.core.production.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.temporal.WorkflowRegistrar;
import io.quarkus.arc.ClientProxy;
import io.temporal.worker.Worker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Registra {@code ProductionPreAuditWorkflow} e suas activities no worker. */
@ApplicationScoped
public class ProductionWorkflowRegistrar implements WorkflowRegistrar {

  @Inject ProductionPreAuditActivitiesImpl activities;

  @Override
  public void register(Worker worker) {
    worker.registerWorkflowImplementationTypes(ProductionPreAuditWorkflowImpl.class);
    worker.registerActivitiesImplementations(ClientProxy.unwrap(activities));
  }
}
