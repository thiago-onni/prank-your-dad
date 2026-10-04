package br.gov.sus.nexus.connectors.sdk.api;

import java.time.Instant;
import java.util.List;

/** Relatório de reconciliação fonte × barramento (OpenAPI {@code ReconciliationEntry}). */
public record ReconciliationReport(
    String connectorId, Period period, List<Entry> entries, Instant checkedAt) {

  public record Entry(String entityType, long sourceCount, long busCount) {
    public long gap() {
      return sourceCount - busCount;
    }
  }

  public ReconciliationReport {
    entries = List.copyOf(entries);
  }

  public long totalGap() {
    return entries.stream().mapToLong(e -> Math.abs(e.gap())).sum();
  }

  public boolean hasGap() {
    return totalGap() > 0;
  }
}
