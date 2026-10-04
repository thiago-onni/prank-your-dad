package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Aprovação humana do lote (PRO-010; OpenAPI {@code ProductionBatchApproval}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionBatchApproval(@NotBlank @Size(min = 10, max = 1000) String justification) {}
