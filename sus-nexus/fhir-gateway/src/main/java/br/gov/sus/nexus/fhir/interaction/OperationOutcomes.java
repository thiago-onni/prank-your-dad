package br.gov.sus.nexus.fhir.interaction;

import br.gov.sus.nexus.fhir.validation.ValidationIssue;
import java.util.List;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;

/** Construção de {@link OperationOutcome} padronizados. */
public final class OperationOutcomes {

  private OperationOutcomes() {}

  public static OperationOutcome single(
      IssueSeverity severity, IssueType code, String message, String expression) {
    OperationOutcome outcome = new OperationOutcome();
    outcome.addIssue(issue(severity, code, message, expression));
    return outcome;
  }

  public static OperationOutcome fromIssues(List<ValidationIssue> issues) {
    OperationOutcome outcome = new OperationOutcome();
    for (ValidationIssue i : issues) {
      outcome.addIssue(issue(i.severity(), i.code(), i.message(), i.expression()));
    }
    if (issues.stream().noneMatch(ValidationIssue::isError)) {
      outcome.addIssue(
          issue(IssueSeverity.INFORMATION, IssueType.INFORMATIONAL, "Validação sem erros", null));
    }
    return outcome;
  }

  /** Resultado de validação bem-sucedida (um issue informativo, conforme a especificação). */
  public static OperationOutcome allOk() {
    return single(IssueSeverity.INFORMATION, IssueType.INFORMATIONAL, "Validação sem erros", null);
  }

  public static OperationOutcomeIssueComponent issue(
      IssueSeverity severity, IssueType code, String message, String expression) {
    OperationOutcomeIssueComponent issue = new OperationOutcomeIssueComponent();
    issue.setSeverity(severity);
    issue.setCode(code);
    issue.setDiagnostics(message);
    issue.setDetails(new CodeableConcept().setText(message));
    if (expression != null && !expression.isBlank()) {
      issue.addExpression(expression);
    }
    return issue;
  }

  public static boolean hasErrors(OperationOutcome outcome) {
    return outcome.getIssue().stream()
        .anyMatch(
            i -> i.getSeverity() == IssueSeverity.ERROR || i.getSeverity() == IssueSeverity.FATAL);
  }
}
