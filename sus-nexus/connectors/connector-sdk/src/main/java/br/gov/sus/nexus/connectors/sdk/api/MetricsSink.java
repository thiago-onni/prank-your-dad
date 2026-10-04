package br.gov.sus.nexus.connectors.sdk.api;

import java.time.Duration;

/** Destino de métricas; a implementação padrão é Micrometer. */
public interface MetricsSink {

  void counter(String name, double increment, String... tags);

  void gauge(String name, double value, String... tags);

  void timer(String name, Duration duration, String... tags);
}
