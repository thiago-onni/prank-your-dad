package br.gov.sus.nexus.core.platform.errors;

/** Conflito de estado (HTTP 409). */
public class ConflictException extends ProblemException {

  public ConflictException(String detail) {
    super(409, "Conflito", detail, "urn:sus-nexus:problem:conflict");
  }
}
