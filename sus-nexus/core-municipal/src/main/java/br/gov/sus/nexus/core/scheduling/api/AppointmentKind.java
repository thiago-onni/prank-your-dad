package br.gov.sus.nexus.core.scheduling.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Tipo do agendamento. */
public enum AppointmentKind {
  DIRECT,
  REGULATED,
  WALK_IN,
  BLOCK,
  WAITLIST,
  RETURN;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static AppointmentKind fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
