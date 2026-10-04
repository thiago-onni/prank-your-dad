package br.gov.sus.nexus.core.sharedkernel;

/** Máscaras de exibição para identificadores e contatos. Nunca retornam o valor em claro. */
public final class Masks {

  private Masks() {}

  /** {@code ***.***.***-12} (dois últimos dígitos). */
  public static String cpf(String digits) {
    String d = Cns.digits(digits);
    if (d == null || d.length() < 2) {
      return "***.***.***-**";
    }
    return "***.***.***-" + d.substring(d.length() - 2);
  }

  /** {@code ***********5678} (quatro últimos dígitos). */
  public static String cns(String digits) {
    String d = Cns.digits(digits);
    if (d == null || d.length() < 4) {
      return "***************";
    }
    return "***********" + d.substring(d.length() - 4);
  }

  /** Identificador genérico: mantém os 3 últimos caracteres. */
  public static String generic(String value) {
    if (value == null || value.isEmpty()) {
      return "***";
    }
    int keep = Math.min(3, value.length());
    return "*".repeat(Math.max(3, value.length() - keep)) + value.substring(value.length() - keep);
  }

  /** Para o sistema do identificador, escolhe a máscara apropriada. */
  public static String forSystem(String system, String value) {
    if (system == null) {
      return generic(value);
    }
    return switch (system.toUpperCase()) {
      case "CPF" -> cpf(value);
      case "CNS" -> cns(value);
      default -> generic(value);
    };
  }

  /** Telefone: mantém os 4 últimos dígitos. */
  public static String phone(String value) {
    String d = Cns.digits(value);
    if (d == null || d.length() < 4) {
      return "****";
    }
    return "*".repeat(d.length() - 4) + d.substring(d.length() - 4);
  }

  /** E-mail: {@code a***@dominio}. */
  public static String email(String value) {
    if (value == null || !value.contains("@")) {
      return "***";
    }
    int at = value.indexOf('@');
    String local = value.substring(0, at);
    String first = local.isEmpty() ? "*" : local.substring(0, 1);
    return first + "***" + value.substring(at);
  }

  /** Nome: mantém a primeira palavra e abrevia o restante ({@code MARIA S. S.}). */
  public static String name(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String[] parts = value.trim().split("\\s+");
    StringBuilder sb = new StringBuilder(parts[0]);
    for (int i = 1; i < parts.length; i++) {
      sb.append(' ').append(parts[i].charAt(0)).append('.');
    }
    return sb.toString();
  }
}
