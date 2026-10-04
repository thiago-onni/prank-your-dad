package br.gov.sus.nexus.core.integration.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Mensagem do ledger espelho (OpenAPI {@code IntegrationMessage}) — nunca carrega o payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntegrationMessageDto(
    String id,
    String connectorId,
    String sourceSystem,
    String sourceRecordId,
    String sourceRecordVersion,
    String entityType,
    IntegrationMessageStatus status,
    String rawRef,
    String rawSha256,
    String correlationId,
    OffsetDateTime receivedAt,
    OffsetDateTime processedAt,
    int attempts,
    ErrorDetail lastError) {}
