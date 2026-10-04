package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.annotation.JsonValue;

/** Sexo (OpenAPI). */
public enum Sex {
  FEMALE,
  MALE,
  UNKNOWN;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  public static Sex fromWire(String v) {
    if (v == null || v.isBlank()) {
      return UNKNOWN;
    }
    return valueOf(v.trim().toUpperCase());
  }
}
