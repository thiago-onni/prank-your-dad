package br.gov.sus.nexus.core.exams.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Mudança de status do pedido (OpenAPI {@code ExamStatusChange}, EXA-002). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExamStatusChange(
    @NotNull @Valid ExamOrderRegistration.SourceRef source,
    @NotNull ExamOrderStatus status,
    @NotNull OffsetDateTime occurredAt,
    String performerCnes,
    OffsetDateTime scheduledAt,
    String appointmentSourceRecordId,
    @Size(max = 500) String reason) {}
