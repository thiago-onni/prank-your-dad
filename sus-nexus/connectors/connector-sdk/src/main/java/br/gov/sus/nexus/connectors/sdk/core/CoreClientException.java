package br.gov.sus.nexus.connectors.sdk.core;

import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;

/** Erro de chamada ao core. 5xx/rede = transitório (retry); 4xx = permanente (DLQ). */
public class CoreClientException extends ConnectorException {

  private final int status;
  private final String problem;

  public CoreClientException(int status, String problem, boolean transientError, Throwable cause) {
    super(
        "publish",
        "core respondeu " + status + (problem == null ? "" : ": " + problem),
        transientError,
        cause);
    this.status = status;
    this.problem = problem;
  }

  public int status() {
    return status;
  }

  public String problem() {
    return problem;
  }

  public static CoreClientException fromStatus(int status, String problem) {
    boolean transientError = status >= 500 || status == 408 || status == 429;
    return new CoreClientException(status, problem, transientError, null);
  }

  public static CoreClientException network(Throwable cause) {
    return new CoreClientException(0, cause.getMessage(), true, cause);
  }
}
