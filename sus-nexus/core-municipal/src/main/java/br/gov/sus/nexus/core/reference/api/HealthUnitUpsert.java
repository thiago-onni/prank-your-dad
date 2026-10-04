package br.gov.sus.nexus.core.reference.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;

/**
 * Comando de upsert idempotente de unidade por (tenant, cnes) — usado pelos conectores (OpenAPI
 * {@code HealthUnitUpsert}).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HealthUnitUpsert(
    String cnes,
    String name,
    String kindCode,
    String kindDescription,
    String address,
    String cityIbge,
    Boolean active,
    String competence,
    Map<String, Object> attributes,
    String sourceSystem) {}
