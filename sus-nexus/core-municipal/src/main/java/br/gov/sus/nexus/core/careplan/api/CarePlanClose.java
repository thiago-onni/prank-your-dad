package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Encerramento do plano. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CarePlanClose(
    @NotBlank @Pattern(regexp = "completed|cancelled") String status,
    @NotBlank @Size(min = 5, max = 500) String reason) {}
