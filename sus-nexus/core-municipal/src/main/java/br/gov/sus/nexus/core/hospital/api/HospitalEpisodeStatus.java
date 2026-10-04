package br.gov.sus.nexus.core.hospital.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Status do episódio hospitalar (OpenAPI {@code HospitalEpisodeStatus}). */
public enum HospitalEpisodeStatus {
  ADMITTED,
  IN_PROGRESS,
  TRANSFERRED,
  DISCHARGED,
  DECEASED,
  CANCELLED;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static HospitalEpisodeStatus fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }

  public boolean isClosed() {
    return this == DISCHARGED || this == DECEASED || this == CANCELLED;
  }
}
