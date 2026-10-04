package br.gov.sus.nexus.core.tasks.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Criação de tarefa (OpenAPI {@code TaskCreate}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskCreate(
    @NotNull TaskType taskType,
    @NotNull TaskPriority priority,
    @NotBlank @Size(max = 200) String title,
    @Size(max = 2000) String description,
    String citizenId,
    @Valid Assignee assignee,
    OffsetDateTime dueAt,
    String slaPolicyId,
    @Valid TaskOrigin origin) {}
