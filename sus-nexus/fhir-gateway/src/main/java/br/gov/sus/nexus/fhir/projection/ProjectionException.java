package br.gov.sus.nexus.fhir.projection;

/** Falha de projeção por evento (mensagem segura para log: sem PII). */
public class ProjectionException extends RuntimeException {
  public ProjectionException(String message) {
    super(message);
  }

  public ProjectionException(String message, Throwable cause) {
    super(message, cause);
  }
}
