package br.gov.sus.nexus.core.terminology.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;

/** Código de terminologia (OpenAPI {@code Code}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CodeDto(
    String system,
    String code,
    String display,
    String competenceFrom,
    String competenceTo,
    Map<String, Object> attributes) {}
