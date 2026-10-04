package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/**
 * Canônico {@code ExamOrder} de {@code contracts/openapi/core-municipal.yaml}. Resultados ({@code
 * results}) são ignorados nesta etapa: {@code DiagnosticReport}/{@code Observation} são FHIR-3.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalExamOrder(
    String id,
    String citizenId,
    String status,
    Instant requestedAt,
    String examCode,
    String codeSystem,
    String examDescription,
    String category,
    String priority,
    String requestingCnes,
    String requestingUnitName,
    String requestingProfessionalId,
    String performerCnes,
    String regulationRequestId,
    String appointmentId,
    Instant scheduledAt,
    Instant performedAt,
    Instant reportedAt,
    String careLine,
    List<String> issues,
    String sourceSystem,
    String sourceRecordId,
    Integer version) {}
