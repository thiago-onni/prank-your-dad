package br.gov.sus.nexus.core.tasks.infrastructure.temporal;

import io.quarkus.arc.ClientProxy;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Registra os workers Temporal (fila {@code sus.temporal.task-queue}) no start da aplicação,
 * somente quando {@code sus.temporal.enabled=true}.
 */
@ApplicationScoped
public class TemporalWorkers {

  private static final Logger LOG = Logger.getLogger(TemporalWorkers.class);

  @Inject TemporalClientProvider provider;
  @Inject TaskSlaActivitiesImpl taskSlaActivities;
  @Inject MpiReviewActivitiesImpl mpiReviewActivities;

  private WorkerFactory factory;

  void onStart(@Observes StartupEvent ev) {
    if (!provider.enabled()) {
      LOG.info("Temporal desabilitado (sus.temporal.enabled=false): workers não registrados");
      return;
    }
    provider
        .client()
        .ifPresent(
            client -> {
              factory = WorkerFactory.newInstance(client);
              Worker worker = factory.newWorker(provider.taskQueue());
              register(worker);
              factory.start();
              LOG.infof("Temporal workers iniciados na fila %s", provider.taskQueue());
            });
  }

  /**
   * Registra workflows e activities (também usado pelos testes com worker do ambiente de teste).
   */
  public void register(Worker worker) {
    worker.registerWorkflowImplementationTypes(
        TaskSlaWorkflowImpl.class, MpiReviewWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        ClientProxy.unwrap(taskSlaActivities), ClientProxy.unwrap(mpiReviewActivities));
  }

  void onStop(@Observes ShutdownEvent ev) {
    if (factory != null) {
      factory.shutdown();
    }
  }
}
