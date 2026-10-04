package br.gov.sus.nexus.core.tasks.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Tarefa (OpenAPI {@code Task}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskDto(
    String id,
    TaskType taskType,
    TaskStatus status,
    TaskPriority priority,
    String title,
    String description,
    String citizenId,
    Assignee assignee,
    OffsetDateTime dueAt,
    String slaPolicyId,
    boolean overdue,
    OffsetDateTime slaBreachedAt,
    TaskOrigin origin,
    String outcome,
    String reason,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime completedAt,
    long version) {}
