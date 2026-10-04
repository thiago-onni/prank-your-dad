package br.gov.sus.nexus.core.integration.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Dead letter (OpenAPI {@code DeadLetter}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeadLetterDto(
    String id,
    String messageId,
    String connectorId,
    String topic,
    String reason,
    String stage,
    int attempts,
    String owner,
    String payloadRef,
    OffsetDateTime createdAt,
    OffsetDateTime triagedAt) {}
