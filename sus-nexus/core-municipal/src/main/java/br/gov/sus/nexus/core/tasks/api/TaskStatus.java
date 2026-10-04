package br.gov.sus.nexus.core.tasks.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Estado da tarefa (OpenAPI {@code TaskStatus}). */
public enum TaskStatus {
  OPEN,
  ASSIGNED,
  IN_PROGRESS,
  COMPLETED,
  CANCELLED,
  ESCALATED;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static TaskStatus fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }

  public boolean isFinal() {
    return this == COMPLETED || this == CANCELLED;
  }
}
