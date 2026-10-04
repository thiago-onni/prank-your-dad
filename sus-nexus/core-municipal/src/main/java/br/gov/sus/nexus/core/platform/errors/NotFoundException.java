package br.gov.sus.nexus.core.platform.errors;

/** Recurso não encontrado (HTTP 404). */
public class NotFoundException extends ProblemException {

  public NotFoundException(String resourceType, String id) {
    super(
        404,
        "Recurso não encontrado",
        resourceType + " não encontrado: " + id,
        "urn:sus-nexus:problem:not-found");
  }
}
