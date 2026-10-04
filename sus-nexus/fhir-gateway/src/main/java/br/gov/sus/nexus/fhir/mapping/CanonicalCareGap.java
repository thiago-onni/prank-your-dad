package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

/**
 * Canônico {@code CareGap} de {@code contracts/openapi/core-municipal.yaml}. Pelo Kafka ({@code
 * sus.caregap.v1}) é montado a partir de {@code data} + {@code subject.municipal_citizen_id}
 * (campos equivalentes, {@code care_gap_id} → {@code id}).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalCareGap(
    String id,
    String citizenId,
    String carePlanId,
    String careLine,
    String gapKind,
    String status,
    Instant expectedBy,
    Integer daysOverdue,
    String protocolId,
    String protocolVersion,
    String healthUnitCnes,
    String teamIne,
    String microarea,
    String taskId,
    Instant detectedAt,
    Instant resolvedAt,
    String resolution,
    Boolean contactValid) {}
