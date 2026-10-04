package br.gov.sus.nexus.core.platform.correlation;

import br.gov.sus.nexus.core.platform.ids.Ulid;
import jakarta.enterprise.context.RequestScoped;

/**
 * Identificador de correlação da requisição. Lido do header {@code X-Correlation-Id} ou gerado;
 * propagado em MDC ({@code correlation_id}), no header de resposta e nos envelopes de evento.
 */
@RequestScoped
public class CorrelationId {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlation_id";

  private String value;

  public String get() {
    if (value == null) {
      value = newValue();
    }
    return value;
  }

  public void set(String value) {
    this.value = value;
  }

  public static String newValue() {
    return "corr_" + Ulid.generate().toLowerCase();
  }
}
