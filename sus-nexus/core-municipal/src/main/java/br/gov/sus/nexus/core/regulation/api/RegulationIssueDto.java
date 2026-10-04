package br.gov.sus.nexus.core.regulation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Pendência (OpenAPI {@code RegulationIssue}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegulationIssueDto(
    String id,
    RegulationIssueKind kind,
    String status,
    String description,
    Origin origin,
    OffsetDateTime createdAt,
    OffsetDateTime resolvedAt) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Origin(String kind, String id, String version) {}
}
