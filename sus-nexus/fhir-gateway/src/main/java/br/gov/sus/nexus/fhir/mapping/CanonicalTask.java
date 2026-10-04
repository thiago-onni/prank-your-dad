package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

/** Canônico {@code Task} de {@code contracts/openapi/core-municipal.yaml}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalTask(
    String id,
    String taskType,
    String status,
    String priority,
    String title,
    String description,
    String citizenId,
    Assignee assignee,
    Instant dueAt,
    String slaPolicyId,
    Boolean overdue,
    Origin origin,
    String outcome,
    String reason,
    Instant slaBreachedAt,
    Instant completedAt,
    Instant createdAt,
    Instant updatedAt,
    Integer version) {

  /** Responsável ({@code kind}: user, team, health_unit, queue). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Assignee(String kind, String id) {}

  /** Origem ({@code kind}: workflow, agent, user, rule, connector). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Origin(String kind, String id, String version) {}
}
