package br.gov.sus.nexus.connectors.sdk.runtime;

import br.gov.sus.nexus.connectors.sdk.api.Connector;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/** Agrega {@link Connector#healthCheck()} em {@code /q/health/ready}. */
@Readiness
@ApplicationScoped
public class ConnectorHealthCheck implements HealthCheck {

  private final Connector connector;

  @Inject
  public ConnectorHealthCheck(Connector connector) {
    this.connector = connector;
  }

  @Override
  public HealthCheckResponse call() {
    String name = "connector:" + connector.descriptor().connectorId();
    HealthCheckResponseBuilder b = HealthCheckResponse.named(name);
    try {
      HealthStatus status = connector.healthCheck();
      b.withData("state", status.state().name().toLowerCase());
      b.withData("connector_version", connector.descriptor().connectorVersion());
      b.withData("source_system", connector.descriptor().sourceSystem());
      status.details().forEach(b::withData);
      return status.isUp() ? b.up().build() : b.down().build();
    } catch (RuntimeException e) {
      return b.down().withData("error", e.getClass().getSimpleName()).build();
    }
  }
}
