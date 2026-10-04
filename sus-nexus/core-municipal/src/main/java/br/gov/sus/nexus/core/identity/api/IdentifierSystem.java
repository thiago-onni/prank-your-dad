package br.gov.sus.nexus.core.identity.api;

/** Sistemas de identificação (OpenAPI {@code IdentifierSystem}). */
public enum IdentifierSystem {
  CNS,
  CPF,
  PEC,
  SISREG,
  ESUS_REGULACAO,
  HIS,
  AIH,
  APAC,
  LOCAL;

  /** CPF e CNS são de alto risco: hash + cifra, nunca em claro fora do reveal. */
  public boolean highRisk() {
    return this == CNS || this == CPF;
  }
}
