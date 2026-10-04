package br.gov.sus.nexus.connectors.rnds.submission;

import java.util.EnumSet;
import java.util.Set;

/** Estados de {@code rnds_submission}. */
public enum RndsSubmissionStatus {
  /** Evento aceito para processamento (claim de idempotência). */
  PENDING,
  /** Última tentativa falhou com erro transitório (5xx/timeout); aguardando retry. */
  RETRYING,
  /** RNDS aceitou (200/201); {@code protocol} preenchido. */
  ACCEPTED,
  /** RNDS rejeitou (4xx); {@code OperationOutcome} armazenado; DLQ sem retry. */
  REJECTED,
  /** Pré-validação local falhou; nada foi enviado; DLQ. */
  INVALID,
  /** Retry esgotado ou erro antes do envio; DLQ (reprocessável). */
  FAILED;

  /** Estados que bloqueiam novo processamento do mesmo {@code event_id}. */
  public static final Set<RndsSubmissionStatus> TERMINAL_OR_IN_FLIGHT =
      EnumSet.of(PENDING, RETRYING, ACCEPTED, REJECTED, INVALID);

  public String apiValue() {
    return name().toLowerCase();
  }
}
