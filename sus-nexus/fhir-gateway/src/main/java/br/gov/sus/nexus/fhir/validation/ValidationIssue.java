package br.gov.sus.nexus.fhir.validation;

import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;

/**
 * Problema encontrado na validação.
 *
 * @param severity severidade FHIR
 * @param code código FHIR do issue
 * @param message diagnóstico legível (sem PII)
 * @param expression FHIRPath do elemento afetado ({@code OperationOutcome.issue.expression})
 */
public record ValidationIssue(
    IssueSeverity severity, IssueType code, String message, String expression) {

  public static ValidationIssue error(IssueType code, String message, String expression) {
    return new ValidationIssue(IssueSeverity.ERROR, code, message, expression);
  }

  public static ValidationIssue warning(IssueType code, String message, String expression) {
    return new ValidationIssue(IssueSeverity.WARNING, code, message, expression);
  }

  public boolean isError() {
    return severity == IssueSeverity.ERROR || severity == IssueSeverity.FATAL;
  }
}
