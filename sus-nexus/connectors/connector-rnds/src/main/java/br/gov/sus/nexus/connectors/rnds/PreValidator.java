package br.gov.sus.nexus.connectors.rnds;

import br.gov.sus.nexus.connectors.sdk.api.ValidationReport;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Pré-validação declarativa ({@code required} do {@link ModelMapping}) sobre os fatos extraídos do
 * Bundle. Roda antes de qualquer chamada à RNDS; mensagens citam só nomes de regras/fatos (nunca
 * valores, que podem ser CPF/CNS).
 */
public final class PreValidator {

  private PreValidator() {}

  public static ValidationReport validate(
      ModelMapping mapping, AssembledBundle assembled, String recordId) {
    ValidationReport.Builder b = ValidationReport.builder();
    for (ModelMapping.RequiredRule rule : mapping.required()) {
      String problem = check(rule, assembled);
      if (problem != null) {
        b.error(recordId, rule.name(), rule.check(), rule.name() + ": " + problem);
      }
    }
    for (String w : assembled.warnings()) {
      b.warning(recordId, "bundle", "assembly", w);
    }
    return b.build();
  }

  private static String check(ModelMapping.RequiredRule rule, AssembledBundle a) {
    switch (rule.check()) {
      case "present" -> {
        for (String f : rule.facts()) if (a.fact(f) != null) return null;
        return "ausente (" + String.join("|", rule.facts()) + ")";
      }
      case "cnes" -> {
        String v = a.fact(rule.facts().get(0));
        if (v == null) return "CNES ausente";
        return Documents.validCnes(v) ? null : "CNES deve ter 7 dígitos";
      }
      case "cns" -> {
        String v = a.fact(rule.facts().get(0));
        if (v == null) return "CNS ausente";
        return Documents.validCns(v) ? null : "CNS com dígito verificador inválido";
      }
      case "cpf" -> {
        String v = a.fact(rule.facts().get(0));
        if (v == null) return "CPF ausente";
        return Documents.validCpf(v) ? null : "CPF com dígito verificador inválido";
      }
      case "cns_or_cpf" -> {
        boolean any = false;
        for (String f : rule.facts()) {
          String v = a.fact(f);
          if (v == null) continue;
          any = true;
          if (f.endsWith("cns") && Documents.validCns(v)) return null;
          if (f.endsWith("cpf") && Documents.validCpf(v)) return null;
        }
        return any ? "CNS/CPF com dígito verificador inválido" : "paciente sem CNS nem CPF";
      }
      case "datetime" -> {
        String v = a.fact(rule.facts().get(0));
        if (v == null) return "data ausente";
        return parseable(v) ? null : "data inválida";
      }
      case "one_of" -> {
        String v = a.fact(rule.facts().get(0));
        if (v == null) return "ausente";
        return rule.values().contains(v) ? null : "valor fora de " + rule.values();
      }
      case "min_count" -> {
        String v = a.fact(rule.facts().get(0));
        int min = rule.values().isEmpty() ? 1 : Integer.parseInt(rule.values().get(0));
        int n = v == null ? 0 : Integer.parseInt(v);
        return n >= min ? null : "mínimo " + min;
      }
      default -> {
        return "check desconhecido";
      }
    }
  }

  private static boolean parseable(String v) {
    try {
      OffsetDateTime.parse(v);
      return true;
    } catch (DateTimeParseException e) {
      try {
        LocalDate.parse(v.length() >= 10 ? v.substring(0, 10) : v);
        return true;
      } catch (DateTimeParseException e2) {
        return false;
      }
    }
  }
}
