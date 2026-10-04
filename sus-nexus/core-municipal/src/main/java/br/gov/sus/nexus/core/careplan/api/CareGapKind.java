package br.gov.sus.nexus.core.careplan.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Tipos de lacuna de cuidado (OpenAPI {@code CareGapKind}). */
public enum CareGapKind {
  CONSULTATION_OVERDUE,
  EXAM_OVERDUE,
  VACCINE_OVERDUE,
  RETURN_OVERDUE,
  NO_CONTACT,
  LOST_TO_FOLLOWUP,
  POST_DISCHARGE_NO_CONTACT;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static CareGapKind fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }

  /** Lacuna por item vencido conforme o tipo do item previsto. */
  public static CareGapKind forItemKind(String itemKind) {
    return switch (itemKind) {
      case "exam" -> EXAM_OVERDUE;
      case "vaccine" -> VACCINE_OVERDUE;
      case "return" -> RETURN_OVERDUE;
      case "home_visit" -> NO_CONTACT;
      default -> CONSULTATION_OVERDUE;
    };
  }
}
