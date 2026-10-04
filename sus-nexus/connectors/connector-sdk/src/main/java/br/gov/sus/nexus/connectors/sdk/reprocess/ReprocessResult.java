package br.gov.sus.nexus.connectors.sdk.reprocess;

/**
 * Resultado do tratamento de um comando de reprocessamento.
 *
 * @param messageId {@code integration_message} afetada (quando conhecida)
 * @param detail motivo legível, sem PII
 */
public record ReprocessResult(Status status, String messageId, String detail) {

  /** Desfechos possíveis (tag {@code result} da métrica {@code connector_reprocess_total}). */
  public enum Status {
    /** Pipeline reexecutado e mensagem publicada. */
    REPROCESSED,
    /** Pipeline reexecutado e mensagem voltou à DLQ. */
    DEAD_LETTERED,
    /** Mensagem já publicada/aceita: nada a fazer (idempotente; estado reespelhado no core). */
    ALREADY_DONE,
    /** Mensagem em estado não reprocessável (em andamento ou rejeição definitiva). */
    NOT_REPROCESSABLE,
    /** Mensagem desconhecida neste conector (ledger e raw zone sem registro). */
    NOT_FOUND,
    /** Comando de outro conector/tenant ou de outro tipo: ignorado. */
    IGNORED,
    /** Mesmo comando ({@code event_id}) já tratado. */
    DUPLICATE,
    /** Envelope fora do contrato. */
    INVALID,
    /** Erro inesperado ao reprocessar (detalhe mascarado); o comando pode ser reenviado. */
    ERROR;

    public String tag() {
      return name().toLowerCase();
    }
  }

  public static ReprocessResult of(Status status, String messageId, String detail) {
    return new ReprocessResult(status, messageId, detail);
  }
}
