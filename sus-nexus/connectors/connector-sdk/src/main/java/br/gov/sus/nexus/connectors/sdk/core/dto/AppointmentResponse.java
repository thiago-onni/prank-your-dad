package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Subconjunto de {@code Appointment} retornado pelo core. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AppointmentResponse(
    String id, String citizenId, String status, String sourceRecordId) {}
