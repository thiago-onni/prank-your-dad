package br.gov.sus.nexus.connectors.sdk.retry;

import java.time.Instant;

/** Registro de DLQ (OpenAPI {@code DeadLetter}). */
public record DeadLetter(
    String id,
    String messageId,
    String connectorId,
    String topic,
    String reason,
    String stage,
    int attempts,
    String owner,
    String payloadRef,
    Instant createdAt) {}
