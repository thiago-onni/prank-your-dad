package br.gov.sus.nexus.core.platform.temporal;

import io.temporal.worker.Worker;

/**
 * Ponto de extensão para os módulos registrarem seus workflows e activities no worker Temporal
 * (fila {@code sus.temporal.task-queue}). Cada módulo expõe um bean {@code @ApplicationScoped} que
 * implementa esta interface; {@link TemporalWorkers} descobre todos via CDI.
 */
public interface WorkflowRegistrar {

  void register(Worker worker);
}
