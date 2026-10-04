package br.gov.sus.nexus.core.terminology.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;

/** Item de upsert de código (OpenAPI {@code CodeUpsert}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CodeUpsert(
    String code,
    String display,
    String competenceFrom,
    String competenceTo,
    Map<String, Object> attributes) {}
