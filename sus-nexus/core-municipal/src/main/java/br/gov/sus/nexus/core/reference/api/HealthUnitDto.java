package br.gov.sus.nexus.core.reference.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Unidade de saúde (OpenAPI {@code HealthUnit}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HealthUnitDto(
    String id,
    String cnes,
    String name,
    String kindCode,
    String kindDescription,
    String address,
    boolean active) {}
