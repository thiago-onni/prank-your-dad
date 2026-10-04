package br.gov.sus.nexus.connectors.sdk.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Máscaras de identificadores de alto risco. CPF: {@code ***.***.***-12}; CNS: {@code
 * ***********1234}. Nunca logar valores em claro.
 */
public final class Pii {

  private static final Pattern CPF =
      Pattern.compile("\\b(\\d{3})\\.?(\\d{3})\\.?(\\d{3})-?(\\d{2})\\b");
  private static final Pattern CNS = Pattern.compile("\\b(\\d{15})\\b");

  private Pii() {}

  public static String maskCpf(String cpf) {
    if (cpf == null) return null;
    String digits = cpf.replaceAll("\\D", "");
    if (digits.length() != 11) return "***";
    return "***.***.***-" + digits.substring(9);
  }

  public static String maskCns(String cns) {
    if (cns == null) return null;
    String digits = cns.replaceAll("\\D", "");
    if (digits.length() != 15) return "***";
    return "***********" + digits.substring(11);
  }

  /** Mascara qualquer CPF/CNS encontrado em texto livre (usado pelo filtro de log). */
  public static String maskText(String text) {
    if (text == null || text.isEmpty()) return text;
    Matcher cns = CNS.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (cns.find()) {
      cns.appendReplacement(sb, Matcher.quoteReplacement(maskCns(cns.group(1))));
    }
    cns.appendTail(sb);
    Matcher cpf = CPF.matcher(sb.toString());
    StringBuilder out = new StringBuilder();
    while (cpf.find()) {
      cpf.appendReplacement(out, Matcher.quoteReplacement("***.***.***-" + cpf.group(4)));
    }
    cpf.appendTail(out);
    return out.toString();
  }
}
