package br.gov.sus.nexus.connectors.sdk.runtime;

/** Headers Camel usados pelo pipeline. */
public final class PipelineHeaders {
  public static final String MESSAGE_ID = "SusMessageId";
  public static final String CORRELATION_ID = "SusCorrelationId";
  public static final String ENTITY_TYPE = "SusEntityType";
  public static final String STAGE = "SusStage";
  public static final String STARTED_AT = "SusStartedAt";
  public static final String RAW_MESSAGE = "SusRawMessage";

  private PipelineHeaders() {}
}
