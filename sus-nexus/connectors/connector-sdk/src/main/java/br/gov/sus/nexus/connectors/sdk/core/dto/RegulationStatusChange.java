package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Payload de {@code POST /api/v1/regulation/requests/{id}/status} e de {@code
 * .../by-source/{system}/{sourceRecordId}/status} (OpenAPI {@code RegulationStatusChange}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegulationStatusChange(
    SourceRef source,
    String status,
    String priority,
    String occurredAt,
    String providerCnes,
    String scheduledAt,
    String appointmentSourceRecordId,
    String regulatorId,
    String reason,
    Boolean returnToOrigin) {}
