package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Item previsto do plano (OpenAPI {@code CarePlanItem}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CarePlanItemDto(
    String id,
    String kind,
    String title,
    String code,
    String codeSystem,
    OffsetDateTime expectedBy,
    Integer periodicityDays,
    String status,
    OffsetDateTime performedAt,
    String evidenceRef,
    boolean overdue) {}
