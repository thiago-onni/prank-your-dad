package br.gov.sus.nexus.core.platform.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/** Finalidade de uso (LGPD) — header {@code X-Purpose-Of-Use}; conforme OpenAPI {@code Purpose}. */
public enum Purpose {
  CARE_COORDINATION("care_coordination"),
  REGULATION("regulation"),
  SCHEDULING("scheduling"),
  IDENTITY_MANAGEMENT("identity_management"),
  PRODUCTION_AUDIT("production_audit"),
  PUBLIC_HEALTH_SURVEILLANCE("public_health_surveillance"),
  MANAGEMENT_ANALYTICS("management_analytics"),
  INTEGRATION_OPERATIONS("integration_operations"),
  SECURITY_AUDIT("security_audit");

  public static final String HEADER = "X-Purpose-Of-Use";

  private final String wire;

  Purpose(String wire) {
    this.wire = wire;
  }

  @JsonValue
  public String wire() {
    return wire;
  }

  @JsonCreator
  public static Purpose fromWire(String value) {
    return parse(value)
        .orElseThrow(() -> new IllegalArgumentException("finalidade inválida: " + value));
  }

  public static Optional<Purpose> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    for (Purpose p : values()) {
      if (p.wire.equalsIgnoreCase(value.trim())) {
        return Optional.of(p);
      }
    }
    return Optional.empty();
  }
}
