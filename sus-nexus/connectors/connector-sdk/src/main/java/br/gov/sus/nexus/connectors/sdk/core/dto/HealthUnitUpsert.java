package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;

/**
 * Item de ingestão de unidade de saúde ({@code POST /api/v1/reference/health-units/upsert}).
 * Contrato: {@code contracts/openapi/core-municipal.yaml} (operação {@code upsertHealthUnits}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
    Map<String, Object> attributes) {}
