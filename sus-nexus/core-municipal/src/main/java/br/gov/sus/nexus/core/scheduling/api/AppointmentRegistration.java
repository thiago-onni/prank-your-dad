package br.gov.sus.nexus.core.scheduling.api;

import br.gov.sus.nexus.core.identity.api.IdentifierSystem;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/**
 * Registro de agendamento vindo de um sistema de origem (OpenAPI {@code AppointmentRegistration}).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AppointmentRegistration(
    @NotNull @Valid SourceRef source,
    @NotNull @Valid CitizenRef citizenRef,
    @NotNull AppointmentStatus status,
    @NotNull AppointmentKind kind,
    String serviceCode,
    String codeSystem,
    String healthUnitCnes,
    String professionalId,
    @NotNull OffsetDateTime scheduledStart,
    OffsetDateTime scheduledEnd,
    OffsetDateTime occurredAt,
    String regulationRequestId,
    String examOrderId,
    String careLine,
    String cancellationReason) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SourceRef(
      @NotBlank String system,
      @NotBlank String connector,
      @NotBlank String sourceRecordId,
      String sourceRecordVersion,
      String cnes) {}

  /** {@code municipal_citizen_id} OU identificador de origem para resolução via MPI. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CitizenRef(
      String municipalCitizenId, IdentifierSystem identifierSystem, String identifierValue) {}
}
