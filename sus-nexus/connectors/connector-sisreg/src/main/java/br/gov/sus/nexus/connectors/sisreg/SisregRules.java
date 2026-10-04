package br.gov.sus.nexus.connectors.sisreg;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Regras de negócio que não cabem no YAML: tipo do pedido e referência ao cidadão. */
public final class SisregRules {

  private SisregRules() {}

  /**
   * Tipo do pedido ({@code RegulationKind}): coluna {@code tipo} quando presente; senão pelo grupo
   * SIGTAP do procedimento (02 = diagnóstico/exame, 03.01.01 = consulta, 04 = cirúrgico, 03.03 =
   * internação clínica... tratado como procedure).
   */
  public static String kind(String tipo, String sigtapCode) {
    String t = tipo == null ? "" : tipo.trim().toUpperCase(Locale.ROOT);
    if (t.startsWith("CONSULT")) return "consultation";
    if (t.startsWith("EXAM")) return "exam";
    if (t.startsWith("CIRURG")) return "surgery";
    if (t.startsWith("INTERNA") || t.startsWith("LEITO") || t.startsWith("AIH")) return "admission";
    if (t.startsWith("PROCED")) return "procedure";
    String code = sigtapCode == null ? "" : sigtapCode.replaceAll("\\D", "");
    if (code.startsWith("030101")) return "consultation";
    if (code.startsWith("02")) return "exam";
    if (code.startsWith("04")) return "surgery";
    return "procedure";
  }

  /** Referência ao cidadão por prioridade: CNS → CPF → código do paciente no SISREG. */
  public static Map<String, Object> citizenRef(String cns, String cpf, String sisregCode) {
    Map<String, Object> ref = new LinkedHashMap<>();
    String cnsDigits = digits(cns);
    String cpfDigits = digits(cpf);
    if (cnsDigits.length() == 15) {
      ref.put("identifier_system", "CNS");
      ref.put("identifier_value", cnsDigits);
    } else if (cpfDigits.length() == 11) {
      ref.put("identifier_system", "CPF");
      ref.put("identifier_value", cpfDigits);
    } else if (sisregCode != null && !sisregCode.isBlank()) {
      ref.put("identifier_system", "SISREG");
      ref.put("identifier_value", sisregCode.trim());
    }
    return ref;
  }

  static String digits(String v) {
    return v == null ? "" : v.replaceAll("\\D", "");
  }
}
