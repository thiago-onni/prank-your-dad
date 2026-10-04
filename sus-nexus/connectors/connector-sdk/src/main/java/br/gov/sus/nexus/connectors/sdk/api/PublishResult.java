package br.gov.sus.nexus.connectors.sdk.api;

import java.util.List;

/** Resultado da publicação no core. */
public record PublishResult(int published, int failed, List<String> coreIds, String detail) {

  public PublishResult {
    coreIds = coreIds == null ? List.of() : List.copyOf(coreIds);
  }

  public static PublishResult of(int published) {
    return new PublishResult(published, 0, List.of(), null);
  }

  public boolean allPublished() {
    return failed == 0;
  }
}
