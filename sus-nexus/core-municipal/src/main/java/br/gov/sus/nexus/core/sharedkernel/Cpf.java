package br.gov.sus.nexus.core.sharedkernel;

import java.util.Optional;

/** CPF (11 dígitos) com validação dos dois dígitos verificadores. */
public record Cpf(String value) {

  public Cpf {
    if (!isValid(value)) {
      throw new IllegalArgumentException("CPF inválido");
    }
  }

  public static Optional<Cpf> parse(String raw) {
    String digits = Cns.digits(raw);
    return isValid(digits) ? Optional.of(new Cpf(digits)) : Optional.empty();
  }

  public static boolean isValid(String raw) {
    String s = Cns.digits(raw);
    if (s == null || s.length() != 11) {
      return false;
    }
    if (s.chars().distinct().count() == 1) {
      return false; // 000.000.000-00, 111.111.111-11 ...
    }
    int d1 = digit(s, 9, 10);
    int d2 = digit(s, 10, 11);
    return d1 == s.charAt(9) - '0' && d2 == s.charAt(10) - '0';
  }

  private static int digit(String s, int length, int startWeight) {
    int soma = 0;
    for (int i = 0; i < length; i++) {
      soma += (s.charAt(i) - '0') * (startWeight - i);
    }
    int resto = soma % 11;
    return resto < 2 ? 0 : 11 - resto;
  }

  /** Gera o CPF completo (11 dígitos) a partir dos 9 primeiros dígitos. */
  public static String withCheckDigits(String nine) {
    int d1 = digit(nine, 9, 10);
    String ten = nine + d1;
    int d2 = digit(ten, 10, 11);
    return ten + d2;
  }

  /** Máscara: {@code ***.***.***-12}. */
  public String masked() {
    return Masks.cpf(value);
  }

  @Override
  public String toString() {
    return masked();
  }
}
