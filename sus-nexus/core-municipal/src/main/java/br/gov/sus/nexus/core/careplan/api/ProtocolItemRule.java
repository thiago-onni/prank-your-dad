package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

/**
 * Item previsto por um protocolo (OpenAPI {@code ProtocolItemRule}). {@code condition} é uma
 * expressão restrita ({@code platform.rules.RuleEvaluator}) sobre atributos do cidadão.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProtocolItemRule(
    @NotBlank
        @Pattern(regexp = "consultation|exam|vaccine|return|home_visit|procedure|education|other")
        String kind,
    @NotBlank String title,
    String code,
    String codeSystem,
    @NotNull @Min(0) Integer dueInDays,
    @Min(1) Integer periodicityDays,
    @Min(0) Integer gapAfterDays,
    @Pattern(regexp = "low|medium|high|urgent") String priority,
    Map<String, Object> condition) {}
