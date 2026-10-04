package br.gov.sus.nexus.connectors.sia;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Regras de negócio do conector SIA/SIH que não cabem em mapeamento declarativo. */
public final class SiaRules {

  static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
  static final Pattern PROD_ID = Pattern.compile("^prod_" + ULID.pattern() + "$");
  static final Pattern CIT_ID = Pattern.compile("^cit_" + ULID.pattern() + "$");

  private SiaRules() {}

  public static String digits(String v) {
    return v == null ? "" : v.replaceAll("\\D", "");
  }

  /**
   * {@code citizen_ref} do registro de produção: {@code municipal_citizen_id} (cit_) quando o
   * sistema de origem já o conhece; senão CNS (15 dígitos) → CPF (11 dígitos) para resolução no MPI
   * do core. Nulo quando nada foi informado (BPA-C não exige cidadão).
   */
  public static Map<String, Object> citizenRef(String citizenId, String cns, String cpf) {
    Map<String, Object> ref = new LinkedHashMap<>();
    String id = citizenId == null ? "" : citizenId.trim();
    if (CIT_ID.matcher(id).matches()) {
      ref.put("municipal_citizen_id", id);
      return ref;
    }
    String c = digits(cns);
    if (!c.isEmpty()) {
      ref.put("identifier_system", "CNS");
      ref.put("identifier_value", c);
      return ref;
    }
    String p = digits(cpf);
    if (!p.isEmpty()) {
      ref.put("identifier_system", "CPF");
      ref.put("identifier_value", p);
      return ref;
    }
    return null;
  }

  /**
   * O core exige exatamente um alvo no retorno ({@code production_record_id}, {@code record_source}
   * ou {@code batch_id}). Precedência: id do barramento (prod_) → vínculo de origem → lote; os
   * demais são removidos do payload. Retorna o alvo escolhido (ou {@code null}).
   */
  public static String resolveOutcomeTarget(Map<String, Object> payload) {
    Object prod = payload.get("production_record_id");
    if (prod != null && !prod.toString().isBlank()) {
      payload.remove("record_source");
      payload.remove("batch_id");
      return "production_record_id";
    }
    payload.remove("production_record_id");
    Object src = payload.get("record_source");
    if (src instanceof Map<?, ?> m
        && m.get("source_record_id") != null
        && !m.get("source_record_id").toString().isBlank()) {
      payload.remove("batch_id");
      return "record_source";
    }
    payload.remove("record_source");
    Object batch = payload.get("batch_id");
    if (batch != null && !batch.toString().isBlank()) return "batch_id";
    payload.remove("batch_id");
    return null;
  }

  /**
   * Valores de largura fixa com casas decimais implícitas ({@code 000000012345} com 2 casas →
   * {@code 123,45}, formato aceito por {@code to_decimal}). Só altera valores compostos só de
   * dígitos.
   */
  public static void applyImplicitDecimals(
      Map<String, String> canonical, String field, String places) {
    if (places == null || places.isBlank()) return;
    String v = canonical.get(field);
    if (v == null || !v.trim().matches("\\d+")) return;
    int n = Integer.parseInt(places.trim());
    if (n <= 0) return;
    String digits = v.trim();
    while (digits.length() <= n) digits = "0" + digits;
    canonical.put(
        field,
        digits.substring(0, digits.length() - n) + "," + digits.substring(digits.length() - n));
  }

  /** Dígito verificador do CNS (definitivo 1/2, provisório 7/8/9) — sem expor o valor. */
  public static boolean validCns(String cns) {
    if (cns == null || !cns.matches("\\d{15}")) return false;
    char first = cns.charAt(0);
    if ("12789".indexOf(first) < 0) return false;
    int sum = 0;
    for (int i = 0; i < 15; i++) sum += (cns.charAt(i) - '0') * (15 - i);
    return sum % 11 == 0;
  }

  /** Dígitos verificadores do CPF. */
  public static boolean validCpf(String cpf) {
    if (cpf == null || !cpf.matches("\\d{11}") || cpf.chars().distinct().count() == 1) return false;
    for (int j = 9; j <= 10; j++) {
      int sum = 0;
      for (int i = 0; i < j; i++) sum += (cpf.charAt(i) - '0') * (j + 1 - i);
      int dv = (sum * 10) % 11 % 10;
      if (dv != cpf.charAt(j) - '0') return false;
    }
    return true;
  }
}
