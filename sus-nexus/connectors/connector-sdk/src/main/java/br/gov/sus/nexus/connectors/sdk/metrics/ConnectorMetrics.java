package br.gov.sus.nexus.connectors.sdk.metrics;

import br.gov.sus.nexus.connectors.sdk.api.MetricsSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Métricas padrão (Micrometer), todas com tag {@code connector_id}: {@code
 * connector_messages_received_total}, {@code connector_messages_processed_total}, {@code
 * connector_messages_failed_total}, {@code connector_processing_latency_ms} e {@code
 * integration_reconciliation_gap_total}.
 */
@ApplicationScoped
public class ConnectorMetrics implements MetricsSink {

  public static final String RECEIVED = "connector_messages_received_total";
  public static final String PROCESSED = "connector_messages_processed_total";
  public static final String FAILED = "connector_messages_failed_total";
  public static final String LATENCY = "connector_processing_latency_ms";
  public static final String RECONCILIATION_GAP = "integration_reconciliation_gap_total";

  private final MeterRegistry registry;
  private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

  @Inject
  public ConnectorMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void received(String connectorId, String entityType) {
    counter(RECEIVED, 1, "connector_id", connectorId, "entity_type", entityType);
  }

  public void processed(String connectorId, String entityType) {
    counter(PROCESSED, 1, "connector_id", connectorId, "entity_type", entityType);
  }

  public void failed(String connectorId, String stage) {
    counter(FAILED, 1, "connector_id", connectorId, "stage", stage);
  }

  public void latency(String connectorId, Duration duration) {
    timer(LATENCY, duration, "connector_id", connectorId);
  }

  public void reconciliationGap(String connectorId, String entityType, long gap) {
    counter(
        RECONCILIATION_GAP, Math.abs(gap), "connector_id", connectorId, "entity_type", entityType);
  }

  @Override
  public void counter(String name, double increment, String... tags) {
    Counter.builder(name).tags(Tags.of(tags)).register(registry).increment(increment);
  }

  @Override
  public void gauge(String name, double value, String... tags) {
    String key = name + Tags.of(tags);
    AtomicLong holder =
        gauges.computeIfAbsent(
            key,
            k -> {
              AtomicLong a = new AtomicLong();
              registry.gauge(name, Tags.of(tags), a);
              return a;
            });
    holder.set((long) value);
  }

  @Override
  public void timer(String name, Duration duration, String... tags) {
    Timer.builder(name).tags(Tags.of(tags)).register(registry).record(duration);
  }

  public double counterValue(String name, String... tags) {
    Counter c = registry.find(name).tags(tags).counter();
    return c == null ? 0 : c.count();
  }
}
