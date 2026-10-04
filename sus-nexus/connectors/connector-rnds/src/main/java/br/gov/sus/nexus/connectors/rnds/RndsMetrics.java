package br.gov.sus.nexus.connectors.rnds;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;

/**
 * Métricas do conector RNDS: {@code connector_rnds_submissions_total{model,result}} (result =
 * accepted | rejected | invalid | failed | retry), {@code connector_rnds_submission_latency} (timer
 * do POST ao EHR, tag model), {@code connector_rnds_events_ignored_total{model,reason}} e {@code
 * connector_rnds_token_requests_total}.
 */
@ApplicationScoped
public class RndsMetrics {

  public static final String SUBMISSIONS = "connector_rnds_submissions_total";
  public static final String LATENCY = "connector_rnds_submission_latency";
  public static final String IGNORED = "connector_rnds_events_ignored_total";

  private final MeterRegistry registry;

  @Inject
  public RndsMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void submission(String model, String result) {
    Counter.builder(SUBMISSIONS)
        .description("Envios à RNDS por modelo e resultado")
        .tag("model", model)
        .tag("result", result)
        .register(registry)
        .increment();
  }

  public void latency(String model, Duration duration) {
    Timer.builder(LATENCY)
        .description("Latência do POST do Bundle ao EHR da RNDS")
        .tag("model", model)
        .publishPercentileHistogram()
        .register(registry)
        .record(duration);
  }

  public void ignored(String model, String reason) {
    Counter.builder(IGNORED)
        .tag("model", model)
        .tag("reason", reason)
        .register(registry)
        .increment();
  }

  public double count(String model, String result) {
    Counter c = registry.find(SUBMISSIONS).tag("model", model).tag("result", result).counter();
    return c == null ? 0 : c.count();
  }

  public double ignoredCount(String model, String reason) {
    Counter c = registry.find(IGNORED).tag("model", model).tag("reason", reason).counter();
    return c == null ? 0 : c.count();
  }
}
