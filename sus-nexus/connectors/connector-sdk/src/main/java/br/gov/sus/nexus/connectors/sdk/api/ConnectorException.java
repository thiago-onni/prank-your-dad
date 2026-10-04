package br.gov.sus.nexus.connectors.sdk.api;

/** Erro de conector. {@link #isTransient()} determina se o pipeline tenta novamente. */
public class ConnectorException extends RuntimeException {

  private final String stage;
  private final boolean transientError;

  public ConnectorException(String stage, String message, boolean transientError, Throwable cause) {
    super(message, cause);
    this.stage = stage;
    this.transientError = transientError;
  }

  public String stage() {
    return stage;
  }

  public boolean isTransient() {
    return transientError;
  }

  public static ConnectorException transientError(String stage, String message, Throwable cause) {
    return new ConnectorException(stage, message, true, cause);
  }

  public static ConnectorException permanent(String stage, String message, Throwable cause) {
    return new ConnectorException(stage, message, false, cause);
  }
}
