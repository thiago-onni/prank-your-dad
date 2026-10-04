package br.gov.sus.nexus.fhir.codec;

/** Erro de parse de um recurso FHIR (JSON malformado, propriedade desconhecida, tipo errado). */
public class FhirParseException extends RuntimeException {

  public FhirParseException(String message, Throwable cause) {
    super(message, cause);
  }

  public FhirParseException(String message) {
    super(message);
  }
}
