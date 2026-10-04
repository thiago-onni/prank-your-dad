package br.gov.sus.nexus.core.identity.domain;

/** Evidência explicável de comparação de um atributo. */
public record MatchEvidence(
    String attribute, String comparison, Agreement agreement, double weight) {

  /** Concordância. */
  public enum Agreement {
    AGREE,
    PARTIAL,
    DISAGREE,
    MISSING;

    public String wire() {
      return name().toLowerCase();
    }
  }
}
