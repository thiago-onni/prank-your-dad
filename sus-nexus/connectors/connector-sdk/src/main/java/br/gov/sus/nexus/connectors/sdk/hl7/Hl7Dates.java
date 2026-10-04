package br.gov.sus.nexus.connectors.lis;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converte timestamps HL7 (TS/DTM: {@code yyyyMMdd[HHmm[ss[.ffff]]][+/-ZZZZ]}) em ISO-8601. */
public final class Hl7Dates {

  private static final Pattern TS =
      Pattern.compile(
          "^(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(?:\\.\\d+)?([+-]\\d{4})?$");

  private Hl7Dates() {}

  /** ISO-8601 com offset; {@code null} se vazio ou inválido. */
  public static String toIso(String ts, ZoneId zone) {
    if (ts == null || ts.isBlank()) return null;
    Matcher m = TS.matcher(ts.trim());
    if (!m.matches()) return null;
    int year = Integer.parseInt(m.group(1));
    int month = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
    int day = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
    int hour = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
    int minute = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
    int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
    try {
      LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, second);
      ZoneOffset offset =
          m.group(7) != null
              ? ZoneOffset.of(m.group(7).substring(0, 3) + ":" + m.group(7).substring(3))
              : zone.getRules().getOffset(ldt);
      return OffsetDateTime.of(ldt, offset).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Data {@code yyyy-MM-dd}; {@code null} se vazio ou inválido. */
  public static String toDate(String ts) {
    if (ts == null || ts.length() < 8) return null;
    try {
      return LocalDate.parse(ts.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE).toString();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
