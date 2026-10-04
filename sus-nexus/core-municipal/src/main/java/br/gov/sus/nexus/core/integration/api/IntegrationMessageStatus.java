package br.gov.sus.nexus.core.integration.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Status de mensagem no pipeline do conector (OpenAPI {@code IntegrationMessageStatus}). */
public enum IntegrationMessageStatus {
  RECEIVED,
  TRANSFORMED,
  VALIDATED,
  PUBLISHED,
  PROCESSED,
  FAILED,
  DEAD_LETTERED,
  REPROCESSING;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static IntegrationMessageStatus fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
