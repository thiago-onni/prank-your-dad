package br.gov.sus.nexus.fhir.persistence;

import java.time.Instant;
import java.util.List;

/** Linha de {@code fhir.fhir_resource} / {@code fhir.fhir_resource_history}. */
public record StoredResource(
    String id,
    String tenantId,
    String resourceType,
    int versionId,
    Instant lastUpdated,
    List<String> profiles,
    String content,
    boolean deleted) {

  public String etag() {
    return "W/\"" + versionId + "\"";
  }
}
