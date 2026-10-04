package br.gov.sus.nexus.fhir.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Conversão de datas FHIR (date, dateTime, instant, com precisão variável) para intervalos {@code
 * [low, high)}. Datas sem fuso são interpretadas em UTC, de forma consistente entre indexação e
 * busca.
 */
public final class FhirDates {

  private FhirDates() {}

  /** Limites usados para períodos abertos. */
  public static final Instant MIN = Instant.parse("1800-01-01T00:00:00Z");

  public static final Instant MAX = Instant.parse("9999-12-31T00:00:00Z");

  /** Intervalo semiaberto {@code [low, high)}. */
  public record Range(Instant low, Instant high) {}

  /** Prefixos de comparação de busca por data. */
  public enum Prefix {
    EQ,
    NE,
    GT,
    LT,
    GE,
    LE,
    SA,
    EB;

    static Optional<Prefix> of(String s) {
      try {
        return Optional.of(valueOf(s.toUpperCase()));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }
  }

  /** Valor de busca já separado em prefixo + intervalo. */
  public record SearchValue(Prefix prefix, Range range) {}

  /** Interpreta um valor de parâmetro de busca ({@code ge2020-01-01}). */
  public static Optional<SearchValue> parseSearchValue(String raw) {
    if (raw == null || raw.length() < 4) {
      return Optional.empty();
    }
    Prefix prefix = Prefix.EQ;
    String value = raw;
    if (!Character.isDigit(raw.charAt(0)) && raw.length() > 2) {
      Optional<Prefix> p = Prefix.of(raw.substring(0, 2));
      if (p.isEmpty()) {
        return Optional.empty();
      }
      prefix = p.get();
      value = raw.substring(2);
    }
    final Prefix pf = prefix;
    return parse(value).map(r -> new SearchValue(pf, r));
  }

  /** Converte uma data FHIR em intervalo conforme sua precisão. */
  public static Optional<Range> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    String v = value.trim();
    try {
      if (v.length() == 4) {
        Year y = Year.parse(v);
        return Optional.of(new Range(start(y.atDay(1)), start(y.plusYears(1).atDay(1))));
      }
      if (v.length() == 7) {
        YearMonth ym = YearMonth.parse(v);
        return Optional.of(new Range(start(ym.atDay(1)), start(ym.plusMonths(1).atDay(1))));
      }
      if (v.length() == 10) {
        LocalDate d = LocalDate.parse(v);
        return Optional.of(new Range(start(d), start(d.plusDays(1))));
      }
      // dateTime / instant
      boolean hasOffset = v.endsWith("Z") || v.matches(".*[+-]\\d{2}:\\d{2}$");
      Instant instant;
      if (hasOffset) {
        instant = OffsetDateTime.parse(v).toInstant();
      } else {
        String local = v;
        if (local.length() == 16) {
          local = local + ":00";
        }
        instant = LocalDateTime.parse(local).toInstant(ZoneOffset.UTC);
      }
      boolean hasFraction = v.contains(".");
      Instant high = hasFraction ? instant.plusMillis(1) : instant.plusSeconds(1);
      return Optional.of(new Range(instant, high));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static Instant start(LocalDate d) {
    return d.atStartOfDay(ZoneOffset.UTC).toInstant();
  }
}
