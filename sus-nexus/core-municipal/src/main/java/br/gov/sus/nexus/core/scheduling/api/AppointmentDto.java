package br.gov.sus.nexus.core.scheduling.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;

/** Agendamento (OpenAPI {@code Appointment}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AppointmentDto(
    String id,
    String citizenId,
    AppointmentStatus status,
    AppointmentKind kind,
    String serviceCode,
    String codeSystem,
    String serviceDescription,
    String healthUnitCnes,
    String professionalId,
    OffsetDateTime scheduledStart,
    OffsetDateTime scheduledEnd,
    String regulationRequestId,
    String examOrderId,
    String careLine,
    String cancellationReason,
    String sourceSystem,
    String sourceRecordId,
    List<StatusEntry> statusHistory,
    long version) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StatusEntry(AppointmentStatus status, OffsetDateTime occurredAt, String reason) {}
}
