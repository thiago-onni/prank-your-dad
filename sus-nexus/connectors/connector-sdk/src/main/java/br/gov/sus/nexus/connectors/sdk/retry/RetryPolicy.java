package br.gov.sus.nexus.connectors.sdk.retry;

import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.FailedMessage;
import br.gov.sus.nexus.connectors.sdk.api.RetryDecision;
import br.gov.sus.nexus.connectors.sdk.config.ConnectorConfig;
import java.time.Duration;

/** Backoff exponencial limitado; erros permanentes nunca são repetidos. */
public record RetryPolicy(
    int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {

  public RetryPolicy {
    if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts >= 1");
    if (multiplier < 1.0) throw new IllegalArgumentException("multiplier >= 1.0");
  }

  public static RetryPolicy from(ConnectorConfig.Retry cfg) {
    return new RetryPolicy(
        cfg.maxAttempts(), cfg.initialBackoff(), cfg.multiplier(), cfg.maxBackoff());
  }

  public static RetryPolicy from(ConnectorDescriptor.RetryPolicySpec spec) {
    return new RetryPolicy(
        spec.maxAttempts(), spec.initialBackoff(), spec.multiplier(), spec.maxBackoff());
  }

  public ConnectorDescriptor.RetryPolicySpec toSpec() {
    return new ConnectorDescriptor.RetryPolicySpec(
        maxAttempts, initialBackoff, multiplier, maxBackoff);
  }

  /** Atraso para a tentativa número {@code attempt} (1 = primeira repetição). */
  public Duration delayFor(int attempt) {
    double factor = Math.pow(multiplier, Math.max(0, attempt - 1));
    long millis = (long) Math.min(initialBackoff.toMillis() * factor, maxBackoff.toMillis());
    return Duration.ofMillis(millis);
  }

  public RetryDecision decide(FailedMessage failed) {
    if (failed.permanent()) {
      return RetryDecision.deadLetter("erro permanente em " + failed.stage());
    }
    if (failed.attempts() >= maxAttempts) {
      return RetryDecision.deadLetter(
          "tentativas esgotadas (" + failed.attempts() + "/" + maxAttempts + ")");
    }
    return RetryDecision.retryAfter(
        delayFor(failed.attempts()), "tentativa " + (failed.attempts() + 1) + "/" + maxAttempts);
  }
}
