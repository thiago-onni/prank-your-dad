package br.gov.sus.nexus.core.platform.ingestion;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;

/** Origem de um lote de ingestão (OpenAPI {@code SourceRef}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BatchSource(
    @NotBlank String system,
    @NotBlank String connector,
    @NotBlank String sourceRecordId,
    String sourceRecordVersion,
    String cnes) {}
