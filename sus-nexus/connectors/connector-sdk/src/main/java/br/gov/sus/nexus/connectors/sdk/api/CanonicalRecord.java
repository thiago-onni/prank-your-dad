package br.gov.sus.nexus.connectors.sdk.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Um registro canônico pronto para publicação no core (payload conforme OpenAPI). */
public record CanonicalRecord(
    String sourceRecordId, String sourceRecordVersion, Map<String, Object> payload) {

  public CanonicalRecord {
    payload =
        payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }
}
