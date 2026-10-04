package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Item de {@code POST /api/v1/regulation/capacity} (OpenAPI {@code ProviderCapacity}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProviderCapacity(
    String providerCnes,
    String providerName,
    String serviceCode,
    String codeSystem,
    String competence,
    Integer offered,
    Integer used,
    Integer available,
    String sourceSystem,
    String updatedAt) {}
