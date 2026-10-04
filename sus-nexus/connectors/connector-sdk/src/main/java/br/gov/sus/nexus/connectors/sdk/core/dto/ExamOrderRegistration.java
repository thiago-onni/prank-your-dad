package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Payload de {@code POST /api/v1/exams/orders} (OpenAPI {@code ExamOrderRegistration}). Upsert por
 * {@code source.source_record_id}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExamOrderRegistration(
    SourceRef source,
    CitizenRef citizenRef,
    String status,
    String requestedAt,
    String examCode,
    String codeSystem,
    String examDescription,
    String category,
    String requestingCnes,
    String requestingProfessionalId,
    String regulationSourceRecordId,
    String appointmentSourceRecordId,
    String careLine,
    String priority,
    String occurredAt) {}
