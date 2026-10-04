package br.gov.sus.nexus.fhir.capability;

/** Interações RESTful FHIR suportadas pelo gateway (código conforme restful-interaction). */
public enum Interaction {
  READ("read"),
  VREAD("vread"),
  SEARCH_TYPE("search-type"),
  CREATE("create"),
  UPDATE("update"),
  PATCH("patch"),
  DELETE("delete"),
  HISTORY_INSTANCE("history-instance"),
  HISTORY_TYPE("history-type");

  private final String code;

  Interaction(String code) {
    this.code = code;
  }

  /** Código em {@code http://hl7.org/fhir/restful-interaction}. */
  public String code() {
    return code;
  }

  public boolean isWrite() {
    return this == CREATE || this == UPDATE || this == PATCH || this == DELETE;
  }

  /** Código de ação do AuditEvent (C, R, U, D, E). */
  public String auditAction() {
    return switch (this) {
      case CREATE -> "C";
      case UPDATE, PATCH -> "U";
      case DELETE -> "D";
      case READ, VREAD, HISTORY_INSTANCE, HISTORY_TYPE -> "R";
      case SEARCH_TYPE -> "E";
    };
  }
}
