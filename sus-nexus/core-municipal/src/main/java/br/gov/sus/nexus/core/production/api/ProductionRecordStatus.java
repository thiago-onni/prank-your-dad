package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Status do registro de produção (OpenAPI {@code ProductionRecordStatus}). */
public enum ProductionRecordStatus {
  GENERATED,
  VALIDATED,
  PENDING,
  EXPORTED,
  TRANSMITTED,
  RECEIVED,
  REJECTED,
  CORRECTED,
  APPROVED,
  PAID;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static ProductionRecordStatus fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }

  /** Ainda em pré-auditoria no barramento (antes de exportar). */
  public boolean inPreAudit() {
    return this == GENERATED || this == VALIDATED || this == PENDING || this == CORRECTED;
  }

  /** Já saiu do barramento (exportado ou com retorno oficial). */
  public boolean submitted() {
    return this == EXPORTED
        || this == TRANSMITTED
        || this == RECEIVED
        || this == APPROVED
        || this == PAID
        || this == REJECTED;
  }
}
