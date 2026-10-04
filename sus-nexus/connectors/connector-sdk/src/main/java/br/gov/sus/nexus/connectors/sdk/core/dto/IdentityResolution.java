package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Resposta de {@code POST /api/v1/citizens}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IdentityResolution(
    String municipalCitizenId,
    String classification,
    String method,
    Double score,
    String ruleVersion,
    String mergeCaseId,
    String registrationState) {}
