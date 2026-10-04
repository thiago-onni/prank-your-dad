package br.gov.sus.nexus.core.hospital.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.temporal.WorkflowRegistrar;
import io.quarkus.arc.ClientProxy;
import io.temporal.worker.Worker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Registra {@code DischargeFollowUpWorkflow} e suas activities no worker. */
@ApplicationScoped
public class HospitalWorkflowRegistrar implements WorkflowRegistrar {

  @Inject DischargeFollowUpActivitiesImpl activities;

  @Override
  public void register(Worker worker) {
    worker.registerWorkflowImplementationTypes(DischargeFollowUpWorkflowImpl.class);
    worker.registerActivitiesImplementations(ClientProxy.unwrap(activities));
  }
}
