package br.gov.sus.nexus.core.exams.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Status do pedido de exame (OpenAPI {@code ExamOrderStatus}). */
public enum ExamOrderStatus {
  REQUESTED,
  AUTHORIZED,
  SCHEDULED,
  COLLECTED,
  PERFORMED,
  REPORTED,
  CANCELLED,
  NOT_PERFORMED;

  @JsonValue
  public String wire() {
    return name().toLowerCase();
  }

  @JsonCreator
  public static ExamOrderStatus fromWire(String v) {
    return valueOf(v.trim().toUpperCase());
  }

  public boolean isTerminal() {
    return this == CANCELLED || this == NOT_PERFORMED;
  }

  /** Ainda sem agendamento. */
  public boolean isAwaitingSchedule() {
    return this == REQUESTED || this == AUTHORIZED;
  }

  /** Já realizado/coletado, aguardando ou com laudo. */
  public boolean isPerformed() {
    return this == COLLECTED || this == PERFORMED || this == REPORTED;
  }

  /** Conta como exame pendente no resumo do cidadão (JOR-008). */
  public boolean isPending() {
    return !(this == REPORTED || isTerminal());
  }
}
