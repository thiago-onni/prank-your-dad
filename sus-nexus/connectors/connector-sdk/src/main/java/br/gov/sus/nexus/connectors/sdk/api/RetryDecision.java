package br.gov.sus.nexus.connectors.sdk.api;

import java.time.Duration;

/** Decisão de retry: tentar novamente após {@code delay} ou enviar à DLQ. */
public record RetryDecision(boolean retry, Duration delay, String reason) {

  public static RetryDecision retryAfter(Duration delay, String reason) {
    return new RetryDecision(true, delay, reason);
  }

  public static RetryDecision deadLetter(String reason) {
    return new RetryDecision(false, Duration.ZERO, reason);
  }
}
