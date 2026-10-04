package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Resposta dos upserts em lote. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UpsertResult(Integer created, Integer updated, Integer unchanged, Integer rejected) {

  public int total() {
    return n(created) + n(updated) + n(unchanged);
  }

  private static int n(Integer i) {
    return i == null ? 0 : i;
  }
}
