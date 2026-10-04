package br.gov.sus.nexus.connectors.sdk.api;

import java.time.Instant;

/** Detalhe de erro de uma mensagem (OpenAPI {@code IntegrationMessage.last_error}). */
public record ErrorDetails(
    String messageId, String code, String message, String stage, Instant occurredAt, int attempts) {

  public static ErrorDetails none(String messageId) {
    return new ErrorDetails(messageId, null, null, null, null, 0);
  }
}
