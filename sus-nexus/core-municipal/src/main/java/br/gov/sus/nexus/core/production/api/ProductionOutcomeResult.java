package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** Resultado da aplicação do retorno oficial (OpenAPI {@code ProductionOutcomeResult}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionOutcomeResult(
    String outcome, String batchId, boolean unchanged, List<Affected> affected) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Affected(String productionRecordId, ProductionRecordStatus status) {}
}
