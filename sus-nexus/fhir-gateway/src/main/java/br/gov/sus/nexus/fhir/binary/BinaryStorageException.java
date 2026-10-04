package br.gov.sus.nexus.fhir.binary;

/** Falha de infraestrutura do object storage (mensagem sem PII). */
public class BinaryStorageException extends RuntimeException {
  public BinaryStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
