package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Payload de {@code POST /api/v1/appointments} (OpenAPI {@code AppointmentRegistration}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AppointmentRegistration(
    SourceRef source,
    CitizenRef citizenRef,
    String status,
    String kind,
    String serviceCode,
    String codeSystem,
    String healthUnitCnes,
    String professionalId,
    String scheduledStart,
    String scheduledEnd,
    String occurredAt,
    String regulationRequestId,
    String examOrderId,
    String cancellationReason) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CitizenRef(
      String municipalCitizenId, String identifierSystem, String identifierValue) {}
}
