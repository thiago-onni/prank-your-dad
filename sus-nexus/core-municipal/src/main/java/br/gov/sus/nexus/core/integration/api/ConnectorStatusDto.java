package br.gov.sus.nexus.core.integration.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Situação de um conector (OpenAPI {@code ConnectorStatus}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConnectorStatusDto(
    String connectorId,
    String connectorVersion,
    String sourceSystem,
    String health,
    String healthDetail,
    OffsetDateTime lastMessageAt,
    OffsetDateTime lastHeartbeatAt,
    @JsonProperty("received_24h") long received24h,
    @JsonProperty("failed_24h") long failed24h,
    long dlqOpen,
    int reconciliationGap) {}
