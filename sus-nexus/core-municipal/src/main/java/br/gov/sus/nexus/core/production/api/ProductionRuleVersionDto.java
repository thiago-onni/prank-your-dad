package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Versão ativada da regra de pré-auditoria (OpenAPI {@code ProductionRuleVersion}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionRuleVersionDto(
    String id,
    String ruleSet,
    String version,
    String label,
    String status,
    int rulesCount,
    int testCasesCount,
    String approvedBy,
    OffsetDateTime effectiveFrom,
    String previousLabel) {}
