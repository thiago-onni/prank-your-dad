package br.gov.sus.nexus.core.regulation.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * Mudança de status recebida do sistema oficial (OpenAPI {@code RegulationStatusChange}): decisão,
 * agendamento, devolução, realização, falta. O barramento só registra (REG-003/007).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegulationStatusChange(
    @NotNull @Valid RegulationRequestRegistration.SourceRef source,
    @NotNull RegulationStatus status,
    RegulationPriority priority,
    @NotNull OffsetDateTime occurredAt,
    String providerCnes,
    OffsetDateTime scheduledAt,
    String appointmentSourceRecordId,
    String regulatorId,
    @Size(max = 500) String reason,
    Boolean returnToOrigin) {}
