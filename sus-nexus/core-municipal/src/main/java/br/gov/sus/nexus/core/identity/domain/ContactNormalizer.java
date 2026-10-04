package br.gov.sus.nexus.core.identity.domain;

import java.util.Locale;

/** Normalização de contatos: telefone em E.164 (+55...), e-mail em minúsculas. */
public final class ContactNormalizer {

  private ContactNormalizer() {}

  public static String normalize(String kind, String value) {
    if (value == null) {
      return null;
    }
    if ("email".equalsIgnoreCase(kind)) {
      return value.trim().toLowerCase(Locale.ROOT);
    }
    return phone(value);
  }

  /** Telefone brasileiro: dígitos; 10/11 dígitos recebem DDI 55; retorna {@code +55...}. */
  public static String phone(String raw) {
    String digits = raw.replaceAll("[^0-9]", "");
    if (digits.isEmpty()) {
      return null;
    }
    if (digits.startsWith("0") && digits.length() > 10) {
      digits = digits.substring(1); // operadora/DDD com zero à esquerda
    }
    if (digits.length() == 10 || digits.length() == 11) {
      digits = "55" + digits;
    }
    return "+" + digits;
  }
}
