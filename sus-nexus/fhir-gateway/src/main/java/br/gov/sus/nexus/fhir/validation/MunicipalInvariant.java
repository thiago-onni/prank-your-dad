package br.gov.sus.nexus.fhir.validation;

import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;

/**
 * Invariante municipal expressa em FHIRPath.
 *
 * @param key identificador estável (ex.: {@code sus-pat-1})
 * @param resourceType tipo ao qual se aplica
 * @param expression expressão FHIRPath que deve ser verdadeira
 * @param message mensagem do issue
 * @param location FHIRPath do elemento reportado em {@code issue.expression}
 * @param severity severidade (erro → 422)
 */
public record MunicipalInvariant(
    String key,
    String resourceType,
    String expression,
    String message,
    String location,
    IssueSeverity severity) {

  public ValidationIssue toIssue() {
    IssueType type = severity == IssueSeverity.ERROR ? IssueType.INVARIANT : IssueType.BUSINESSRULE;
    return new ValidationIssue(severity, type, "[" + key + "] " + message, location);
  }
}
