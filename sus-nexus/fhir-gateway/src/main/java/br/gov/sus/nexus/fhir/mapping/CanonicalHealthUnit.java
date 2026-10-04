package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Forma canônica de unidade de saúde ({@code HealthUnit} no OpenAPI do core). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalHealthUnit(
    String id,
    String cnes,
    String name,
    String kindCode,
    String kindDescription,
    String address,
    Boolean active) {}
