package br.gov.sus.nexus.core.tasks.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Duration;

/** Política de SLA vigente ({@code tasks.sla_policy}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SlaPolicyDto(
    String id,
    TaskType taskType,
    TaskPriority priority,
    Duration dueIn,
    Duration escalateAfter,
    Assignee escalateTo,
    String policyVersion) {}
