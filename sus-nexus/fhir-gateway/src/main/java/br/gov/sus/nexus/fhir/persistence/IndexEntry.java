package br.gov.sus.nexus.fhir.persistence;

import java.time.Instant;

/** Entrada de índice de busca extraída de um recurso. */
public sealed interface IndexEntry {

  String param();

  record Token(String param, String system, String code) implements IndexEntry {}

  record Str(String param, String valueNorm) implements IndexEntry {}

  record Date(String param, Instant low, Instant high) implements IndexEntry {}

  record Ref(String param, String targetType, String targetId) implements IndexEntry {}

  /** Quantidade ({@code Quantity.value} com unidade codificada opcional). */
  record Quantity(String param, String system, String code, java.math.BigDecimal value)
      implements IndexEntry {}
}
