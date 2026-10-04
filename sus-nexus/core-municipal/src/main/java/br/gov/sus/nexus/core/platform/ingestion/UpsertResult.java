package br.gov.sus.nexus.core.platform.ingestion;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Resultado de upsert em lote (OpenAPI {@code UpsertResult}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UpsertResult(int created, int updated, int unchanged, int rejected) {

  /** Acumulador mutável usado durante o processamento do lote. */
  public static final class Counter {
    private int created;
    private int updated;
    private int unchanged;
    private int rejected;

    public void created() {
      created++;
    }

    public void updated() {
      updated++;
    }

    public void unchanged() {
      unchanged++;
    }

    public void rejected() {
      rejected++;
    }

    public UpsertResult result() {
      return new UpsertResult(created, updated, unchanged, rejected);
    }
  }
}
