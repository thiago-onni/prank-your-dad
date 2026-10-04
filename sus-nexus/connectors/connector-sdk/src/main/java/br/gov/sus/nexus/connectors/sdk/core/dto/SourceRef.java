package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** {@code source} de CitizenRegistration/AppointmentRegistration (OpenAPI). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SourceRef(
    String system,
    String connector,
    String sourceRecordId,
    String sourceRecordVersion,
    String cnes) {}
