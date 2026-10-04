package br.gov.sus.nexus.core.scheduling.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Status do agendamento (OpenAPI {@code AppointmentStatus}). */
public enum AppointmentStatus {
  PROPOSED,
  BOOKED,
  CONFIRMED,
  ARRIVED,
  FULFILLED,
  CANCELLED,
  NOSHOW,
  WAITLIST;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static AppointmentStatus fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }

  /** Status que ocupam vaga/aguardam realização (AGE-004 considera apenas estes). */
  public boolean isActive() {
    return this == PROPOSED
        || this == BOOKED
        || this == CONFIRMED
        || this == ARRIVED
        || this == WAITLIST;
  }
}
