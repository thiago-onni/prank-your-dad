package br.gov.sus.nexus.fhir.interaction;

import br.gov.sus.nexus.fhir.validation.ValidationIssue;
import java.util.List;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;

/** Exceção de domínio FHIR: carrega o status HTTP e o {@link OperationOutcome} de resposta. */
public class FhirException extends RuntimeException {

  private final int status;
  private final transient OperationOutcome outcome;

  public FhirException(int status, OperationOutcome outcome) {
    super(summary(outcome));
    this.status = status;
    this.outcome = outcome;
  }

  public FhirException(int status, IssueType code, String message) {
    this(status, OperationOutcomes.single(IssueSeverity.ERROR, code, message, null));
  }

  public FhirException(int status, IssueType code, String message, String expression) {
    this(status, OperationOutcomes.single(IssueSeverity.ERROR, code, message, expression));
  }

  public int status() {
    return status;
  }

  public OperationOutcome outcome() {
    return outcome;
  }

  private static String summary(OperationOutcome outcome) {
    if (outcome == null || outcome.getIssue().isEmpty()) {
      return "FHIR error";
    }
    return outcome.getIssueFirstRep().getDiagnostics();
  }

  // ---- fábricas -------------------------------------------------------------------------------

  public static FhirException notFound(String type, String id) {
    return new FhirException(
        404, IssueType.NOTFOUND, "Recurso " + type + "/" + id + " não encontrado");
  }

  public static FhirException versionNotFound(String type, String id, String vid) {
    return new FhirException(
        404,
        IssueType.NOTFOUND,
        "Versão " + vid + " do recurso " + type + "/" + id + " não encontrada");
  }

  public static FhirException gone(String type, String id) {
    return new FhirException(
        410, IssueType.DELETED, "Recurso " + type + "/" + id + " foi removido");
  }

  public static FhirException invalid(String message) {
    return new FhirException(400, IssueType.INVALID, message);
  }

  public static FhirException invalid(String message, String expression) {
    return new FhirException(400, IssueType.INVALID, message, expression);
  }

  public static FhirException structure(String message) {
    return new FhirException(400, IssueType.STRUCTURE, message);
  }

  public static FhirException notSupported(String message) {
    return new FhirException(404, IssueType.NOTSUPPORTED, message);
  }

  public static FhirException methodNotAllowed(String message) {
    return new FhirException(405, IssueType.NOTSUPPORTED, message);
  }

  public static FhirException conflict(String message) {
    return new FhirException(412, IssueType.CONFLICT, message);
  }

  public static FhirException unauthorized(String message) {
    return new FhirException(401, IssueType.LOGIN, message);
  }

  public static FhirException forbidden(String message) {
    return new FhirException(403, IssueType.FORBIDDEN, message);
  }

  public static FhirException unprocessable(List<ValidationIssue> issues) {
    return new FhirException(422, OperationOutcomes.fromIssues(issues));
  }

  public static FhirException badRequest(List<ValidationIssue> issues) {
    return new FhirException(400, OperationOutcomes.fromIssues(issues));
  }
}
