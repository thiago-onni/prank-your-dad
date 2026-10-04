package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Nova versão da regra de pré-auditoria {@code production-validation} (OpenAPI {@code
 * ProductionRuleVersionCreate}): definição jsonb ({@code kind=validation_rules}), casos de teste
 * obrigatórios (executados antes de ativar) e justificativa.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionRuleVersionCreate(
    @NotNull JsonNode definition,
    @NotNull JsonNode testCases,
    @NotBlank @Size(min = 10, max = 1000) String justification) {}
