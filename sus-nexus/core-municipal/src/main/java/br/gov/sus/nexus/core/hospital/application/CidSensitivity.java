package br.gov.sus.nexus.core.hospital.application;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Classifica o diagnóstico principal (CID-10) como {@code highly_restricted} quando pertence aos
 * capítulos/intervalos configurados em {@code sus.privacy.highly-restricted-cid-prefixes} (padrão:
 * F = saúde mental, B20-B24 = HIV, O = gestação). Entradas: prefixo ({@code F}, {@code O}) ou
 * intervalo de categorias ({@code B20-B24}).
 */
@ApplicationScoped
public class CidSensitivity {

  @ConfigProperty(
      name = "sus.privacy.highly-restricted-cid-prefixes",
      defaultValue = "F,B20-B24,O")
  List<String> prefixes;

  public boolean isHighlyRestricted(String cid) {
    String code = normalize(cid);
    if (code == null) {
      return false;
    }
    for (String p : prefixes) {
      String rule = p.trim().toUpperCase(Locale.ROOT);
      if (rule.isEmpty()) {
        continue;
      }
      int dash = rule.indexOf('-');
      if (dash > 0) {
        String from = rule.substring(0, dash).trim();
        String to = rule.substring(dash + 1).trim();
        String category = code.length() > 3 ? code.substring(0, 3) : code;
        if (category.compareTo(from) >= 0 && category.compareTo(to) <= 0) {
          return true;
        }
      } else if (code.startsWith(rule)) {
        return true;
      }
    }
    return false;
  }

  /** Normaliza {@code f32.1} → {@code F321}? Não: mantém a categoria legível ({@code F32.1} → {@code F32.1}). */
  public static String normalize(String cid) {
    if (cid == null || cid.isBlank()) {
      return null;
    }
    return cid.trim().toUpperCase(Locale.ROOT).replace(" ", "");
  }
}
