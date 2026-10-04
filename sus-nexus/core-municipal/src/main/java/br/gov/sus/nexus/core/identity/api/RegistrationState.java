package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.annotation.JsonValue;

/** Estado cadastral do cidadão. */
public enum RegistrationState {
  VALIDATED,
  DIVERGENT,
  INCOMPLETE,
  DUPLICATE,
  PENDING;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  public static RegistrationState fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
