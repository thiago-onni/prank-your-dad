package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * OpenAPI {@code ExamStatusChange} ({@code POST /api/v1/exams/orders/{id}/status}). O core só expõe
 * esse endpoint por id interno; conectores sem o id publicam a mudança de status como upsert do
 * pedido ({@link ExamOrderRegistration}). Mantido para uso por ingestão via evento.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExamStatusChange(
    SourceRef source,
    String status,
    String occurredAt,
    String performerCnes,
    String scheduledAt,
    String appointmentSourceRecordId,
    String reason) {}
