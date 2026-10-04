package br.gov.sus.nexus.core.sharedkernel;

import java.util.Optional;

/**
 * Cartão Nacional de Saúde (15 dígitos).
 *
 * <ul>
 *   <li>Definitivo (inicia em 1 ou 2): derivado do PIS/PASEP (11 dígitos) + "000"/"001" + DV, com
 *       soma ponderada por pesos 15..5, resto mod 11.
 *   <li>Provisório (inicia em 7, 8 ou 9): soma dos 15 dígitos ponderada por pesos 15..1 deve ser
 *       múltiplo de 11.
 * </ul>
 */
public record Cns(String value) {

  public Cns {
    if (!isValid(value)) {
      throw new IllegalArgumentException("CNS inválido");
    }
  }

  public static Optional<Cns> parse(String raw) {
    String digits = digits(raw);
    return isValid(digits) ? Optional.of(new Cns(digits)) : Optional.empty();
  }

  public static boolean isValid(String raw) {
    String s = digits(raw);
    if (s == null || s.length() != 15) {
      return false;
    }
    char first = s.charAt(0);
    if (first == '1' || first == '2') {
      return isValidDefinitive(s);
    }
    if (first == '7' || first == '8' || first == '9') {
      return isValidProvisional(s);
    }
    return false;
  }

  private static boolean isValidDefinitive(String s) {
    String pis = s.substring(0, 11);
    return fromPis(pis).equals(s);
  }

  /** Gera o CNS definitivo correspondente a um PIS/PASEP de 11 dígitos. */
  public static String fromPis(String pis) {
    int soma = 0;
    for (int i = 0; i < 11; i++) {
      soma += (pis.charAt(i) - '0') * (15 - i);
    }
    int resto = soma % 11;
    int dv = 11 - resto;
    if (dv == 11) {
      dv = 0;
    }
    if (dv == 10) {
      soma += 2;
      resto = soma % 11;
      dv = 11 - resto;
      if (dv == 11) {
        dv = 0;
      }
      return pis + "001" + dv;
    }
    return pis + "000" + dv;
  }

  private static boolean isValidProvisional(String s) {
    int soma = 0;
    for (int i = 0; i < 15; i++) {
      soma += (s.charAt(i) - '0') * (15 - i);
    }
    return soma % 11 == 0;
  }

  /** Máscara: {@code ***********5678}. */
  public String masked() {
    return Masks.cns(value);
  }

  static String digits(String raw) {
    if (raw == null) {
      return null;
    }
    return raw.replaceAll("[^0-9]", "");
  }

  @Override
  public String toString() {
    return masked();
  }
}
