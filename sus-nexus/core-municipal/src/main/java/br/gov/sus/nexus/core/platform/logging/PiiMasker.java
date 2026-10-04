package br.gov.sus.nexus.core.platform.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mascara CPF (11 dígitos, com ou sem pontuação) e CNS (15 dígitos) em texto livre. Usado pelo
 * filtro de log e por qualquer saída que não possa conter PII.
 */
public final class PiiMasker {

  /** CPF formatado 000.000.000-00 ou 11 dígitos contíguos. */
  private static final Pattern CPF =
      Pattern.compile("(?<![0-9])(\\d{3})\\.?(\\d{3})\\.?(\\d{3})-?(\\d{2})(?![0-9])");

  /** CNS: 15 dígitos contíguos (ou com espaços 000 0000 0000 0000). */
  private static final Pattern CNS =
      Pattern.compile("(?<![0-9])(\\d{3}) ?(\\d{4}) ?(\\d{4}) ?(\\d{4})(?![0-9])");

  private PiiMasker() {}

  public static String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String out = maskCns(text);
    return maskCpf(out);
  }

  static String maskCns(String text) {
    Matcher m = CNS.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String last4 = m.group(4);
      m.appendReplacement(sb, Matcher.quoteReplacement("***********" + last4));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  static String maskCpf(String text) {
    Matcher m = CPF.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String last2 = m.group(4);
      m.appendReplacement(sb, Matcher.quoteReplacement("***.***.***-" + last2));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
