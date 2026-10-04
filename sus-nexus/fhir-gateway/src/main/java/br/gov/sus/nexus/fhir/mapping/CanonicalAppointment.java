package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/** Canônico {@code Appointment} de {@code contracts/openapi/core-municipal.yaml}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalAppointment(
    String id,
    String citizenId,
    String status,
    String kind,
    String serviceCode,
    String codeSystem,
    String serviceDescription,
    String healthUnitCnes,
    String professionalId,
    Instant scheduledStart,
    Instant scheduledEnd,
    String regulationRequestId,
    String examOrderId,
    String careLine,
    String cancellationReason,
    String sourceSystem,
    String sourceRecordId,
    Integer version,
    List<StatusHistory> statusHistory) {

  /** Entrada do histórico de status. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StatusHistory(String status, Instant occurredAt, String reason) {}
}
