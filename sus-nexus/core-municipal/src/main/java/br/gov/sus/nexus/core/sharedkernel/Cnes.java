package br.gov.sus.nexus.core.sharedkernel;

import java.util.Optional;
import java.util.regex.Pattern;

/** Código CNES do estabelecimento (7 dígitos). */
public record Cnes(String value) {

  private static final Pattern PATTERN = Pattern.compile("^[0-9]{7}$");

  public Cnes {
    if (!isValid(value)) {
      throw new IllegalArgumentException("CNES inválido");
    }
  }

  public static boolean isValid(String raw) {
    return raw != null && PATTERN.matcher(raw.trim()).matches();
  }

  public static Optional<Cnes> parse(String raw) {
    return isValid(raw) ? Optional.of(new Cnes(raw.trim())) : Optional.empty();
  }
}
