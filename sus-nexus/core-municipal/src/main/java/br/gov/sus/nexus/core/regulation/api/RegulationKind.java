package br.gov.sus.nexus.core.regulation.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Tipo da solicitação regulatória (OpenAPI {@code RegulationKind}). */
public enum RegulationKind {
  CONSULTATION,
  EXAM,
  PROCEDURE,
  SURGERY,
  ADMISSION;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static RegulationKind fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
