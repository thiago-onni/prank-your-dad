package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;

/** Prazo de apresentação da competência (PRO-007; OpenAPI {@code ProductionDeadline}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionDeadlineDto(
    String competence,
    OffsetDateTime deadlineAt,
    String status,
    long daysRemaining,
    List<Integer> alertDays,
    String configuredBy,
    long pendingRecords,
    long validatedRecords) {}
