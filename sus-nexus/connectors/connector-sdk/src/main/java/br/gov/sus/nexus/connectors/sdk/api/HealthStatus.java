package br.gov.sus.nexus.connectors.sdk.api;

import java.util.Map;

/** Saúde do conector; espelha {@code ConnectorStatus.health} do OpenAPI. */
public record HealthStatus(State state, Map<String, String> details) {

  public enum State {
    HEALTHY,
    DEGRADED,
    DOWN,
    UNKNOWN
  }

  public HealthStatus {
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  public static HealthStatus healthy() {
    return new HealthStatus(State.HEALTHY, Map.of());
  }

  public static HealthStatus degraded(String reason) {
    return new HealthStatus(State.DEGRADED, Map.of("reason", reason));
  }

  public static HealthStatus down(String reason) {
    return new HealthStatus(State.DOWN, Map.of("reason", reason));
  }

  public boolean isUp() {
    return state == State.HEALTHY || state == State.DEGRADED;
  }
}
