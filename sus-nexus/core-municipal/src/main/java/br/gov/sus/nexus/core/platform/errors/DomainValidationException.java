package br.gov.sus.nexus.core.platform.errors;

import java.util.List;

/** Violação de regra de validação de domínio (HTTP 422). */
public class DomainValidationException extends ProblemException {

  public DomainValidationException(String detail) {
    this(detail, List.of());
  }

  public DomainValidationException(String detail, List<FieldError> errors) {
    super(422, "Dados inválidos", detail, "urn:sus-nexus:problem:validation", errors);
  }

  public static DomainValidationException field(String field, String message) {
    return new DomainValidationException(
        field + ": " + message, List.of(new FieldError(field, message)));
  }
}
