package br.gov.sus.nexus.core.identity.api;

import br.gov.sus.nexus.core.platform.security.Purpose;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corpos de requisição do módulo identity. */
public final class Requests {

  private Requests() {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Reveal(
      @NotNull Purpose purpose, @NotBlank @Size(min = 10, max = 500) String justification) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Revealed(IdentifierSystem system, String value) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Merge(
      @NotBlank String survivingCitizenId, @NotBlank @Size(min = 10, max = 500) String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Reason(@NotBlank @Size(min = 10, max = 500) String reason) {}
}
