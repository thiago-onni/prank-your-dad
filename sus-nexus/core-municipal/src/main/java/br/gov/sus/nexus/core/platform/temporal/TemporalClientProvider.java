package br.gov.sus.nexus.core.platform.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Cliente Temporal: conecta ao servidor apenas com {@code sus.temporal.enabled=true} (prod). Em
 * dev/test fica vazio — starters viram no-op — ou recebe um cliente de teste ({@code
 * TestWorkflowEnvironment}) via {@link #useClient}.
 */
@ApplicationScoped
public class TemporalClientProvider {

  private static final Logger LOG = Logger.getLogger(TemporalClientProvider.class);

  @ConfigProperty(name = "sus.temporal.enabled", defaultValue = "false")
  boolean enabled;

  @ConfigProperty(name = "sus.temporal.target", defaultValue = "localhost:7233")
  String target;

  @ConfigProperty(name = "sus.temporal.namespace", defaultValue = "default")
  String namespace;

  @ConfigProperty(name = "sus.temporal.task-queue", defaultValue = "tasks")
  String taskQueue;

  private volatile WorkflowClient client;
  private volatile WorkflowServiceStubs stubs;

  public boolean enabled() {
    return enabled;
  }

  public String taskQueue() {
    return taskQueue;
  }

  public Optional<WorkflowClient> client() {
    if (client == null && enabled) {
      synchronized (this) {
        if (client == null) {
          stubs =
              WorkflowServiceStubs.newServiceStubs(
                  WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
          client =
              WorkflowClient.newInstance(
                  stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
          LOG.infof("Temporal conectado a %s (namespace %s)", target, namespace);
        }
      }
    }
    return Optional.ofNullable(client);
  }

  /** Injeta um cliente externo (testes com {@code TestWorkflowEnvironment}). */
  public void useClient(WorkflowClient external) {
    this.client = external;
  }

  @PreDestroy
  void shutdown() {
    if (stubs != null) {
      stubs.shutdownNow();
    }
  }
}
