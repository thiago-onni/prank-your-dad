package br.gov.sus.nexus.fhir.capability;

/** Interações RESTful FHIR suportadas pelo gateway (código conforme restful-interaction). */
public enum Interaction {
  READ("read"),
  VREAD("vread"),
  SEARCH_TYPE("search-type"),
  CREATE("create"),
  UPDATE("update"),
  HISTORY_INSTANCE("history-instance");

  private final String code;

  Interaction(String code) {
    this.code = code;
  }

  /** Código em {@code http://hl7.org/fhir/restful-interaction}. */
  public String code() {
    return code;
  }

  public boolean isWrite() {
    return this == CREATE || this == UPDATE;
  }

  /** Código de ação do AuditEvent (C, R, U, D, E). */
  public String auditAction() {
    return switch (this) {
      case CREATE -> "C";
      case UPDATE -> "U";
      case READ, VREAD, HISTORY_INSTANCE -> "R";
      case SEARCH_TYPE -> "E";
    };
  }
}
