package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Pattern;

/** Origem do plano (OpenAPI {@code CarePlan.origin}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CarePlanOrigin(
    @Pattern(regexp = "professional|rule|workflow|hospital_discharge") String kind, String id) {

  public static CarePlanOrigin hospitalDischarge(String episodeId) {
    return new CarePlanOrigin("hospital_discharge", episodeId);
  }
}
