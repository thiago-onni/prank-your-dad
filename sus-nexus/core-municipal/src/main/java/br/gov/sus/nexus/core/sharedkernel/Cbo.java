package br.gov.sus.nexus.core.sharedkernel;

import java.util.Optional;
import java.util.regex.Pattern;

/** Código CBO da ocupação (6 dígitos). */
public record Cbo(String value) {

  private static final Pattern PATTERN = Pattern.compile("^[0-9]{6}$");

  public Cbo {
    if (!isValid(value)) {
      throw new IllegalArgumentException("CBO inválido");
    }
  }

  public static boolean isValid(String raw) {
    return raw != null && PATTERN.matcher(raw.trim()).matches();
  }

  public static Optional<Cbo> parse(String raw) {
    return isValid(raw) ? Optional.of(new Cbo(raw.trim())) : Optional.empty();
  }
}
