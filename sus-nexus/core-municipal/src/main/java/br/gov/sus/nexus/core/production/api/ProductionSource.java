package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

/** Origem do registro/retorno (OpenAPI {@code SourceRefInput}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionSource(
    @NotBlank String system,
    @NotBlank String connector,
    @NotBlank String sourceRecordId,
    String sourceRecordVersion,
    String cnes) {}
