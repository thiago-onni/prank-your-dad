package br.gov.sus.nexus.core.regulation.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Registro de pendência documental/administrativa ({@code POST /requests/{id}/issues}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegulationIssueCreate(
    @NotNull RegulationIssueKind kind,
    @NotBlank @Size(max = 1000) String description,
    @Valid Origin origin) {

  /** Origem: humano, agente (com aprovação humana prévia no ai-service) ou regra. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Origin(
      @Pattern(regexp = "user|agent|rule") String kind, String id, String version) {}
}
