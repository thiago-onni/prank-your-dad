package br.gov.sus.nexus.core.exams.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Pendências do pedido de exame (EXA-004/005/009). */
public enum ExamIssue {
  NOT_SCHEDULED,
  NO_RESULT_FOLLOWUP,
  RESULT_PENDING,
  INTEGRATION_FAILURE,
  INCONCLUSIVE,
  CRITICAL;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static ExamIssue fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
