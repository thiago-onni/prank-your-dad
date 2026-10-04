package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Resultado da resolução de identidade (OpenAPI {@code IdentityResolution}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IdentityResolution(
    String municipalCitizenId,
    MatchClassification classification,
    MatchMethod method,
    Double score,
    String ruleVersion,
    String mergeCaseId,
    RegistrationState registrationState) {

  /** 201 criado; 200 vinculado; 202 caso de revisão aberto. */
  public int httpStatus() {
    return switch (classification) {
      case NEW -> 201;
      case CONFIRMED -> 200;
      default -> 202;
    };
  }
}
