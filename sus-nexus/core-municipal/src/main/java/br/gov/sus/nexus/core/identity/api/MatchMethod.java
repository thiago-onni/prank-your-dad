package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.annotation.JsonValue;

/** Método que produziu a resolução (conforme citizen.v1.schema.json). */
public enum MatchMethod {
  DETERMINISTIC_CNS,
  DETERMINISTIC_CPF,
  DETERMINISTIC_SOURCE_LINK,
  DETERMINISTIC_DEMOGRAPHICS,
  PROBABILISTIC,
  MANUAL,
  NONE;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  public static MatchMethod fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
