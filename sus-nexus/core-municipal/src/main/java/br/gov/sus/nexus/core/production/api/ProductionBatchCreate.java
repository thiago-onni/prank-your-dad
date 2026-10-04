package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Geração de lote (OpenAPI {@code ProductionBatchCreate}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionBatchCreate(
    @NotBlank @Pattern(regexp = "^[0-9]{4}(0[1-9]|1[0-2])$") String competence,
    @NotBlank @Pattern(regexp = "^[0-9]{7}$") String cnes,
    @NotNull ProductionKind kind) {}
