package br.gov.sus.nexus.connectors.sdk.core.dto;

import java.util.List;

/** Corpo de {@code POST /api/v1/regulation/capacity}. */
public record ProviderCapacityBatch(List<ProviderCapacity> items) {

  public ProviderCapacityBatch {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
