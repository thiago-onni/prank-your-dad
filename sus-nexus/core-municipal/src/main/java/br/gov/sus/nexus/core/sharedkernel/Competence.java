package br.gov.sus.nexus.core.sharedkernel;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Pattern;

/** Competência SUS no formato {@code AAAAMM}. Comparável cronologicamente. */
public record Competence(String value) implements Comparable<Competence> {

  private static final Pattern PATTERN = Pattern.compile("^[0-9]{6}$");

  public Competence {
    if (!isValid(value)) {
      throw new IllegalArgumentException("competência inválida (AAAAMM)");
    }
  }

  public static boolean isValid(String raw) {
    if (raw == null || !PATTERN.matcher(raw).matches()) {
      return false;
    }
    int month = Integer.parseInt(raw.substring(4));
    return month >= 1 && month <= 12;
  }

  public static Optional<Competence> parse(String raw) {
    return isValid(raw) ? Optional.of(new Competence(raw)) : Optional.empty();
  }

  public static Competence of(YearMonth ym) {
    return new Competence(String.format("%04d%02d", ym.getYear(), ym.getMonthValue()));
  }

  public static Competence current() {
    return of(YearMonth.from(LocalDate.now()));
  }

  public YearMonth toYearMonth() {
    return YearMonth.of(
        Integer.parseInt(value.substring(0, 4)), Integer.parseInt(value.substring(4)));
  }

  /** {@code from <= this <= to} (to nulo = aberto). */
  public boolean isWithin(String from, String to) {
    boolean afterFrom = from == null || value.compareTo(from) >= 0;
    boolean beforeTo = to == null || value.compareTo(to) <= 0;
    return afterFrom && beforeTo;
  }

  @Override
  public int compareTo(Competence o) {
    return value.compareTo(o.value);
  }
}
