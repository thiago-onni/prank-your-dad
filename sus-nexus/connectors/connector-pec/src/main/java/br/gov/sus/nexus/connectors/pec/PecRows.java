package br.gov.sus.nexus.connectors.pec;

import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.parse.DelimitedParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converte o bruto de uma mensagem PEC (linha CSV com cabeçalho, ou JSON de uma linha JDBC) em um
 * mapa {@code coluna_minúscula → texto}. Datas/timestamps JDBC são normalizados para {@code
 * yyyy-MM-dd} e {@code yyyy-MM-dd HH:mm:ss}.
 */
public final class PecRows {

  public static final String CSV = "text/csv";
  public static final String JSON = "application/json";

  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private PecRows() {}

  public static Map<String, String> fromRaw(
      RawMessage raw, ObjectMapper mapper, Charset charset, char delimiter) {
    if (JSON.equals(raw.contentType())) {
      try {
        Map<String, Object> row =
            mapper.readValue(raw.content(), new TypeReference<Map<String, Object>>() {});
        return normalize(row);
      } catch (IOException e) {
        throw new UncheckedIOException("linha JDBC inválida", e);
      }
    }
    List<Map<String, String>> rows =
        new DelimitedParser(delimiter, true, List.of()).parse(raw.content(), charset);
    if (rows.isEmpty()) throw new IllegalArgumentException("mensagem CSV sem linha de dados");
    Map<String, String> out = new LinkedHashMap<>();
    rows.get(0).forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
    return out;
  }

  /** Normaliza tipos JDBC (Date/Timestamp/Number) para texto. */
  public static Map<String, String> normalize(Map<String, ?> row) {
    Map<String, String> out = new LinkedHashMap<>();
    row.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), text(v)));
    return out;
  }

  static String text(Object v) {
    if (v == null) return "";
    if (v instanceof Timestamp ts) return ts.toLocalDateTime().format(TS);
    if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
    if (v instanceof LocalDate d) return d.toString();
    if (v instanceof java.time.LocalDateTime ldt) return ldt.format(TS);
    if (v instanceof Number n) {
      double d = n.doubleValue();
      return d == Math.rint(d) && !(n instanceof Double)
          ? String.valueOf(n.longValue())
          : n.toString();
    }
    if (v instanceof Boolean b) return b ? "1" : "0";
    return v.toString().trim();
  }
}
