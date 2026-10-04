package br.gov.sus.nexus.core.audit.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;

/** Item da trilha de acessos (OpenAPI {@code AccessLogEntry}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AccessLogEntry(
    String id,
    String actorId,
    List<String> actorRoles,
    String action,
    String resourceType,
    String resourceId,
    String citizenId,
    String purpose,
    String decision,
    boolean breakGlass,
    String correlationId,
    OffsetDateTime occurredAt) {}
