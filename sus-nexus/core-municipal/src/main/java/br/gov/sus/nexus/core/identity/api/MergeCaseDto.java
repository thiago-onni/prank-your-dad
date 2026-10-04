package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;

/** Caso de revisão/fusão (OpenAPI {@code MergeCase}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MergeCaseDto(
    String id,
    MergeCaseStatus status,
    String reason,
    Double score,
    String ruleVersion,
    List<CitizenSummary> candidates,
    List<Evidence> evidence,
    List<String> conflicts,
    OffsetDateTime openedAt,
    OffsetDateTime decidedAt,
    String decidedBy,
    String decisionReason,
    String mergeId) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Evidence(String attribute, String comparison, String agreement, Double weight) {}
}
