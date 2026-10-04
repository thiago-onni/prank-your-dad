package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.annotation.JsonValue;

/** Confiança na identidade do registro. */
public enum IdentityConfidence {
  CONFIRMED,
  PROBABLE,
  PENDING,
  DIVERGENT;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  public static IdentityConfidence fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
