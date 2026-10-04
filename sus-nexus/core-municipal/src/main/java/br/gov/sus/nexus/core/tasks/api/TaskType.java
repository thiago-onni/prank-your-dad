package br.gov.sus.nexus.core.tasks.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Tipos de tarefa (conforme {@code task/task.v1.schema.json}). */
public enum TaskType {
  MPI_REVIEW,
  ACTIVE_SEARCH,
  NO_SHOW_RECOVERY,
  EXAM_NOT_SCHEDULED,
  EXAM_RESULT_FOLLOWUP,
  POST_DISCHARGE_FOLLOWUP,
  REGULATION_PENDING_DOCUMENT,
  PRODUCTION_ISSUE,
  INTEGRATION_ERROR,
  CARE_GAP,
  GENERIC;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static TaskType fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
