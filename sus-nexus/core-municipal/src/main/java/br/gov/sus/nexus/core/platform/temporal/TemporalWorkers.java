package br.gov.sus.nexus.core.platform.temporal;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Registra os workers Temporal (fila {@code sus.temporal.task-queue}) no start da aplicação,
 * somente quando {@code sus.temporal.enabled=true}. Os workflows/activities de cada módulo são
 * registrados pelos seus {@link WorkflowRegistrar}.
 */
@ApplicationScoped
public class TemporalWorkers {

  private static final Logger LOG = Logger.getLogger(TemporalWorkers.class);

  @Inject TemporalClientProvider provider;
  @Inject Instance<WorkflowRegistrar> registrars;

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
   * Registra workflows e activities de todos os módulos (também usado pelos testes com worker do
   * ambiente de teste).
   */
  public void register(Worker worker) {
    for (WorkflowRegistrar registrar : registrars) {
      registrar.register(worker);
    }
  }

  void onStop(@Observes ShutdownEvent ev) {
    if (factory != null) {
      factory.shutdown();
    }
  }
}
