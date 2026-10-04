package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;

/** Item de ingestão de terminologia (OpenAPI {@code Code} sem {@code system}, que vai na URL). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CodeUpsert(
    String code,
    String display,
    String competenceFrom,
    String competenceTo,
    Map<String, Object> attributes) {}
