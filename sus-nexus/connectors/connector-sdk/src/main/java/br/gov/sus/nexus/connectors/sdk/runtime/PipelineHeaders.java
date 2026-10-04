package br.gov.sus.nexus.connectors.sdk.runtime;

/** Headers Camel usados pelo pipeline. */
public final class PipelineHeaders {
  public static final String MESSAGE_ID = "SusMessageId";
  public static final String CORRELATION_ID = "SusCorrelationId";
  public static final String ENTITY_TYPE = "SusEntityType";
  public static final String STAGE = "SusStage";
  public static final String STARTED_AT = "SusStartedAt";
  public static final String RAW_MESSAGE = "SusRawMessage";

  /**
   * Id ({@code msg_...}) da {@code integration_message} sendo reprocessada: o runtime reabre a
   * mesma mensagem do ledger (sem nova gravação na raw zone) em vez de criar outra.
   */
  public static final String REPROCESS_MESSAGE_ID = "SusReprocessMessageId";

  private PipelineHeaders() {}
}
