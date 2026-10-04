package br.gov.sus.nexus.core.platform.errors;

import java.util.List;

/** Exceção base de domínio/aplicação mapeada para RFC 9457 (application/problem+json). */
public class ProblemException extends RuntimeException {

  /** Erro de campo (validação). */
  public record FieldError(String field, String message) {}

  private final int status;
  private final String title;
  private final String type;
  private final List<FieldError> errors;

  public ProblemException(int status, String title, String detail, String type) {
    this(status, title, detail, type, List.of());
  }

  public ProblemException(
      int status, String title, String detail, String type, List<FieldError> errors) {
    super(detail);
    this.status = status;
    this.title = title;
    this.type = type;
    this.errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public int status() {
    return status;
  }

  public String title() {
    return title;
  }

  public String type() {
    return type;
  }

  public String detail() {
    return getMessage();
  }

  public List<FieldError> errors() {
    return errors;
  }
}
