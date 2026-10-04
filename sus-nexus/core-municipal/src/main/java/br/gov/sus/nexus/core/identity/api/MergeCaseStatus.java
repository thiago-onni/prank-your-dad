package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.annotation.JsonValue;

/** Status do caso de revisão/fusão. */
public enum MergeCaseStatus {
  OPEN,
  IN_REVIEW,
  MERGED,
  REJECTED,
  UNMERGED;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  public static MergeCaseStatus fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
