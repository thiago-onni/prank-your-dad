package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.annotation.JsonValue;

/** Classificação do resultado da resolução de identidade. */
public enum MatchClassification {
  CONFIRMED,
  PROBABLE,
  PENDING,
  REJECTED,
  NEW;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  public static MatchClassification fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
