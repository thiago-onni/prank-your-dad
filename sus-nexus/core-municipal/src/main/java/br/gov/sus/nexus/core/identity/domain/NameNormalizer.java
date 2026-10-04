package br.gov.sus.nexus.core.identity.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalização de nomes para matching: maiúsculas, sem acentos, só letras e espaços, partículas
 * removidas (DA, DE, DO, DAS, DOS, E...), sufixos canonizados (JUNIOR→JR, FILHO, NETO).
 */
public final class NameNormalizer {

  private static final Set<String> PARTICLES =
      Set.of(
          "DA", "DE", "DO", "DAS", "DOS", "E", "DI", "DEL", "DELLA", "DU", "VAN", "VON", "LA", "LE",
          "Y");

  private static final Map<String, String> SUFFIXES =
      Map.of("JUNIOR", "JR", "JR.", "JR", "FILHO", "FILHO", "NETO", "NETO", "SOBRINHO", "SOBRINHO");

  private NameNormalizer() {}

  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String upper = stripAccents(raw).toUpperCase(Locale.ROOT);
    String lettersOnly = upper.replaceAll("[^A-Z ]", " ").trim();
    if (lettersOnly.isEmpty()) {
      return null;
    }
    List<String> tokens = new ArrayList<>();
    for (String t : lettersOnly.split("\\s+")) {
      if (t.isEmpty() || PARTICLES.contains(t)) {
        continue;
      }
      tokens.add(SUFFIXES.getOrDefault(t, t));
    }
    return tokens.isEmpty() ? null : String.join(" ", tokens);
  }

  public static String stripAccents(String s) {
    String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
    return decomposed.replaceAll("\\p{M}+", "");
  }

  public static String firstToken(String normalized) {
    if (normalized == null) {
      return null;
    }
    int i = normalized.indexOf(' ');
    return i < 0 ? normalized : normalized.substring(0, i);
  }

  public static String lastToken(String normalized) {
    if (normalized == null) {
      return null;
    }
    int i = normalized.lastIndexOf(' ');
    return i < 0 ? normalized : normalized.substring(i + 1);
  }
}
