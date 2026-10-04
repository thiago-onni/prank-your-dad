package br.gov.sus.nexus.core.identity.domain;

import java.util.Map;

/**
 * Parâmetros do matching probabilístico (Fellegi-Sunter): probabilidades m/u por campo, limiares de
 * Jaro-Winkler e limiares de decisão. Carregados de {@code sus.mpi.*}.
 */
public record MatcherParameters(
    String ruleVersion,
    double thresholdHigh,
    double thresholdLow,
    double jaroWinklerAgree,
    double jaroWinklerPartial,
    Map<String, FieldWeight> weights) {

  /** Probabilidades m (concordância entre pares verdadeiros) e u (concordância ao acaso). */
  public record FieldWeight(double m, double u) {
    public double agreeWeight() {
      return log2(m / u);
    }

    public double disagreeWeight() {
      return log2((1 - m) / (1 - u));
    }

    private static double log2(double x) {
      return Math.log(x) / Math.log(2);
    }
  }

  public FieldWeight weight(String field) {
    FieldWeight w = weights.get(field);
    if (w == null) {
      throw new IllegalStateException("peso m/u não configurado para o campo " + field);
    }
    return w;
  }
}
