package br.gov.sus.nexus.connectors.sdk.mapping;

/** Erro de mapeamento (permanente: o registro vai para DLQ sem retry). */
public class MappingException extends RuntimeException {
  public MappingException(String message, Throwable cause) {
    super(message, cause);
  }
}
