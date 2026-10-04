package br.gov.sus.nexus.core.integration.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** Heartbeat/registro de conector (cliente técnico). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConnectorHeartbeat(
    @NotBlank String connectorVersion,
    @NotBlank String sourceSystem,
    String health,
    String detail,
    Map<String, Object> descriptor,
    Map<String, Object> metrics) {}
