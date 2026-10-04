package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Instrumento de registro da produção (OpenAPI {@code ProductionKind}). */
public enum ProductionKind {
  BPA_C("BPA-C"),
  BPA_I("BPA-I"),
  APAC("APAC"),
  AIH("AIH");

  private final String instrument;

  ProductionKind(String instrument) {
    this.instrument = instrument;
  }

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  /** Nome do instrumento no atributo SIGTAP {@code instrumentos}. */
  public String instrument() {
    return instrument;
  }

  /** BPA-I, APAC e AIH são individualizados: exigem cidadão identificado (CNS/CPF). */
  public boolean individualized() {
    return this != BPA_C;
  }

  @JsonCreator
  public static ProductionKind fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }
}
