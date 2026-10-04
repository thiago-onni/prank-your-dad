package br.gov.sus.nexus.core.exams.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Resultado (OpenAPI {@code ExamResult}) — só metadados. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExamResultDto(
    String id,
    OffsetDateTime reportedAt,
    String status,
    boolean critical,
    String performerCnes,
    boolean hasDocument,
    int observationsCount,
    String followupTaskId,
    String sourceSystem) {}
