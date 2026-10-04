package br.gov.sus.nexus.core.regulation.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Tipo de pendência (OpenAPI {@code RegulationIssue.kind}). */
public enum RegulationIssueKind {
  MISSING_DOCUMENT,
  MISSING_FIELD,
  CLINICAL_JUSTIFICATION,
  DUPLICATE,
  OTHER,
  SLA_BREACHED,
  NO_CAPACITY,
  EXPIRED;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static RegulationIssueKind fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }

  /** Pendências que o filtro {@code issue=incomplete} agrupa (REG-005). */
  public boolean isIncomplete() {
    return this == MISSING_DOCUMENT || this == MISSING_FIELD || this == CLINICAL_JUSTIFICATION;
  }
}
