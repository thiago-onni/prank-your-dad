package br.gov.sus.nexus.core.regulation.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Prioridade regulatória — definida SEMPRE pelo sistema oficial (REG-009). */
public enum RegulationPriority {
  ELECTIVE(0),
  PRIORITY(1),
  URGENT(2),
  EMERGENCY(3);

  private final int rank;

  RegulationPriority(int rank) {
    this.rank = rank;
  }

  /** Ordem para {@code sort=priority_desc}. */
  public int rank() {
    return rank;
  }

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static RegulationPriority fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
