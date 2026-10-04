package br.gov.sus.nexus.core.regulation.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Status da solicitação regulatória (OpenAPI {@code RegulationStatus}). */
public enum RegulationStatus {
  REQUESTED,
  PENDING_DOCUMENTS,
  RETURNED,
  UNDER_REVIEW,
  AUTHORIZED,
  DENIED,
  SCHEDULED,
  CANCELLED,
  NO_SHOW,
  PERFORMED,
  EXPIRED;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static RegulationStatus fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }

  /** Pedido ainda na fila (sem desfecho): conta como aberto no cockpit e no resumo do cidadão. */
  public boolean isOpen() {
    return this == REQUESTED
        || this == PENDING_DOCUMENTS
        || this == RETURNED
        || this == UNDER_REVIEW
        || this == AUTHORIZED;
  }

  /** Decisão do regulador já registrada (encerra o relógio de SLA de decisão). */
  public boolean isDecided() {
    return !(this == REQUESTED
        || this == PENDING_DOCUMENTS
        || this == RETURNED
        || this == UNDER_REVIEW);
  }

  /** Estado final do ciclo. */
  public boolean isTerminal() {
    return this == DENIED
        || this == CANCELLED
        || this == PERFORMED
        || this == EXPIRED
        || this == NO_SHOW;
  }
}
