package br.gov.sus.nexus.connectors.sdk.api;

/** Lote reprovado na validação: erro permanente (não há retry). */
public class ValidationException extends ConnectorException {

  private final ValidationReport report;

  public ValidationException(ValidationReport report) {
    super("validate", "lote inválido: " + summarize(report), false, null);
    this.report = report;
  }

  public ValidationReport report() {
    return report;
  }

  private static String summarize(ValidationReport report) {
    return report.errors().stream()
        .limit(5)
        .map(i -> i.field() + "=" + i.code())
        .reduce((a, b) -> a + ", " + b)
        .orElse("sem detalhes");
  }
}
