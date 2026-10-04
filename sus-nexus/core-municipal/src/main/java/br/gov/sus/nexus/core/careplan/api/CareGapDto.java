package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Lacuna de cuidado (OpenAPI {@code CareGap}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CareGapDto(
    String id,
    String citizenId,
    String citizenDisplayName,
    String carePlanId,
    String careLine,
    CareGapKind gapKind,
    String status,
    OffsetDateTime expectedBy,
    Integer daysOverdue,
    String protocolId,
    String protocolVersion,
    String healthUnitCnes,
    String teamIne,
    String microarea,
    String taskId,
    OffsetDateTime detectedAt,
    OffsetDateTime resolvedAt,
    String resolution,
    Boolean contactValid) {}
