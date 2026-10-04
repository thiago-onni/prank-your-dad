package br.gov.sus.nexus.connectors.sdk.api;

import java.util.ArrayList;
import java.util.List;

/** Resultado da validação de um lote. Erros bloqueiam a publicação; avisos não. */
public record ValidationReport(List<Issue> issues) {

  public enum Severity {
    ERROR,
    WARNING
  }

  public record Issue(
      Severity severity, String sourceRecordId, String field, String code, String message) {}

  public ValidationReport {
    issues = List.copyOf(issues);
  }

  public static ValidationReport valid() {
    return new ValidationReport(List.of());
  }

  public boolean isValid() {
    return issues.stream().noneMatch(i -> i.severity() == Severity.ERROR);
  }

  public List<Issue> errors() {
    return issues.stream().filter(i -> i.severity() == Severity.ERROR).toList();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final List<Issue> issues = new ArrayList<>();

    public Builder error(String recordId, String field, String code, String message) {
      issues.add(new Issue(Severity.ERROR, recordId, field, code, message));
      return this;
    }

    public Builder warning(String recordId, String field, String code, String message) {
      issues.add(new Issue(Severity.WARNING, recordId, field, code, message));
      return this;
    }

    public Builder required(String recordId, String field, Object value) {
      if (value == null || (value instanceof String s && s.isBlank())) {
        error(recordId, field, "required", "campo obrigatório ausente: " + field);
      }
      return this;
    }

    public ValidationReport build() {
      return new ValidationReport(issues);
    }
  }
}
