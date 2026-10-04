package br.gov.sus.nexus.core.tasks.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Prioridade da tarefa. */
public enum TaskPriority {
  LOW,
  MEDIUM,
  HIGH,
  URGENT;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static TaskPriority fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
