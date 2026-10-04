package br.gov.sus.nexus.fhir.persistence;

import java.text.Normalizer;
import java.util.Locale;

/** Normalização de strings para busca: minúsculas, sem acentos, espaços colapsados. */
public final class StringNormalizer {

  private StringNormalizer() {}

  public static String normalize(String value) {
    if (value == null) {
      return "";
    }
    String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
    String noMarks = decomposed.replaceAll("\\p{M}+", "");
    return noMarks.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }
}
