package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;

/** Plano de cuidado (OpenAPI {@code CarePlan}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CarePlanDto(
    String id,
    String citizenId,
    String careLine,
    String status,
    String protocolId,
    String protocolVersion,
    String healthUnitCnes,
    String teamIne,
    String responsibleProfessionalId,
    CarePlanOrigin origin,
    List<CarePlanItemDto> items,
    long openGaps,
    OffsetDateTime startAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    String closedReason,
    long version) {}
