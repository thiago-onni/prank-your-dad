package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Versão de protocolo (OpenAPI {@code Protocol}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProtocolDto(
    String id,
    String careLine,
    String name,
    String version,
    String status,
    String description,
    Map<String, Object> eligibility,
    List<ProtocolItemRule> items,
    Integer lostToFollowupDays,
    Integer testCasesCount,
    String approvedBy,
    OffsetDateTime effectiveFrom,
    OffsetDateTime createdAt,
    Boolean global) {}
