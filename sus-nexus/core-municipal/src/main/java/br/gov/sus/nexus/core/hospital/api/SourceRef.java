package br.gov.sus.nexus.core.hospital.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

/** Origem do registro (OpenAPI {@code SourceRefInput}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SourceRef(
    @NotBlank String system,
    @NotBlank String connector,
    @NotBlank String sourceRecordId,
    String sourceRecordVersion,
    String cnes) {}
