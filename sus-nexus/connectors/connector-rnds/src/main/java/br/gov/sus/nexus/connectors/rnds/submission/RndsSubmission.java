package br.gov.sus.nexus.connectors.rnds.submission;

import java.time.Instant;

/**
 * Registro local {@code rnds_submission}: um por {@code event_id} (chave de idempotência). Nunca
 * guarda PII: só hash do Bundle, protocolo/identificador devolvido pela RNDS e resumo do {@code
 * OperationOutcome} mascarado.
 *
 * @param attempts envios efetivos ao EHR (POST); 0 = nunca chegou à RNDS
 */
public record RndsSubmission(
    String eventId,
    String model,
    String sourceId,
    String integrationMessageId,
    String bundleSha256,
    RndsSubmissionStatus status,
    Integer httpStatus,
    String protocol,
    String outcomeSummary,
    String operationOutcome,
    int attempts,
    Instant createdAt,
    Instant updatedAt,
    Instant submittedAt) {

  public static RndsSubmission pending(String eventId, String model, String sourceId) {
    Instant now = Instant.now();
    return new RndsSubmission(
        eventId,
        model,
        sourceId,
        null,
        null,
        RndsSubmissionStatus.PENDING,
        null,
        null,
        null,
        null,
        0,
        now,
        now,
        null);
  }

  public RndsSubmission withMessage(String messageId) {
    return new RndsSubmission(
        eventId,
        model,
        sourceId,
        messageId,
        bundleSha256,
        status,
        httpStatus,
        protocol,
        outcomeSummary,
        operationOutcome,
        attempts,
        createdAt,
        Instant.now(),
        submittedAt);
  }

  public RndsSubmission withStatus(RndsSubmissionStatus next, String summary) {
    return new RndsSubmission(
        eventId,
        model,
        sourceId,
        integrationMessageId,
        bundleSha256,
        next,
        httpStatus,
        protocol,
        summary,
        operationOutcome,
        attempts,
        createdAt,
        Instant.now(),
        submittedAt);
  }

  /** Registra uma tentativa de envio (POST) com o hash do Bundle. */
  public RndsSubmission attempt(String sha256) {
    return new RndsSubmission(
        eventId,
        model,
        sourceId,
        integrationMessageId,
        sha256,
        status,
        httpStatus,
        protocol,
        outcomeSummary,
        operationOutcome,
        attempts + 1,
        createdAt,
        Instant.now(),
        Instant.now());
  }

  public RndsSubmission withResponse(
      RndsSubmissionStatus next, int http, String protocolId, String summary, String outcomeJson) {
    return new RndsSubmission(
        eventId,
        model,
        sourceId,
        integrationMessageId,
        bundleSha256,
        next,
        http,
        protocolId,
        summary,
        outcomeJson,
        attempts,
        createdAt,
        Instant.now(),
        submittedAt);
  }
}
