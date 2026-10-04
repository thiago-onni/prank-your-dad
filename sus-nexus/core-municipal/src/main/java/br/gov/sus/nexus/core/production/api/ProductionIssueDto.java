package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Pendência de pré-auditoria (OpenAPI {@code ProductionIssue}). Sem dado do cidadão. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionIssueDto(
    String id,
    String productionRecordId,
    String ruleId,
    String ruleVersion,
    String severity,
    String field,
    String message,
    String status,
    String origin,
    String taskId,
    OffsetDateTime createdAt,
    OffsetDateTime resolvedAt,
    String resolutionNote,
    String competence,
    String cnes,
    ProductionKind kind,
    String procedureCode,
    ProductionRecordStatus recordStatus) {}
