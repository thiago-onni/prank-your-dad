package br.gov.sus.nexus.connectors.rnds;

/** Validação de dígito verificador de CNS, CPF e formato de CNES. */
public final class Documents {

  private Documents() {}

  public static String digits(String value) {
    return value == null ? "" : value.replaceAll("\\D", "");
  }

  /** CNS definitivo (1/2) ou provisório (7/8/9), com dígito verificador. */
  public static boolean validCns(String value) {
    String cns = digits(value);
    if (cns.length() != 15) return false;
    char first = cns.charAt(0);
    if (first == '1' || first == '2') {
      String pis = cns.substring(0, 11);
      int sum = 0;
      for (int i = 0; i < 11; i++) sum += (pis.charAt(i) - '0') * (15 - i);
      int dv = 11 - (sum % 11);
      if (dv == 11) dv = 0;
      String expected;
      if (dv == 10) {
        sum += 2;
        dv = 11 - (sum % 11);
        expected = pis + "001" + dv;
      } else {
        expected = pis + "000" + dv;
      }
      return expected.equals(cns);
    }
    if (first == '7' || first == '8' || first == '9') {
      int sum = 0;
      for (int i = 0; i < 15; i++) sum += (cns.charAt(i) - '0') * (15 - i);
      return sum % 11 == 0;
    }
    return false;
  }

  public static boolean validCpf(String value) {
    String cpf = digits(value);
    if (cpf.length() != 11 || cpf.chars().distinct().count() == 1) return false;
    return checkDigit(cpf, 9) == cpf.charAt(9) - '0' && checkDigit(cpf, 10) == cpf.charAt(10) - '0';
  }

  private static int checkDigit(String cpf, int length) {
    int sum = 0;
    for (int i = 0; i < length; i++) sum += (cpf.charAt(i) - '0') * (length + 1 - i);
    int dv = (sum * 10) % 11;
    return dv == 10 ? 0 : dv;
  }

  public static boolean validCnes(String value) {
    return value != null && value.matches("\\d{7}");
  }
}
