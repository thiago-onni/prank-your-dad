package br.gov.sus.nexus.connectors.sdk.mapping;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Transformação declarativa aplicada a um valor de origem. */
public sealed interface Transformation {

  Object apply(Object value, MappingContext ctx);

  /** Remove espaços nas pontas. */
  record Trim() implements Transformation {
    @Override
    public Object apply(Object value, MappingContext ctx) {
      return value == null ? null : value.toString().trim();
    }
  }

  record Upper() implements Transformation {
    @Override
    public Object apply(Object value, MappingContext ctx) {
      return value == null ? null : value.toString().toUpperCase(Locale.ROOT);
    }
  }

  record Lower() implements Transformation {
    @Override
    public Object apply(Object value, MappingContext ctx) {
      return value == null ? null : value.toString().toLowerCase(Locale.ROOT);
    }
  }

  /** Remove acentos (NFD + remoção de marcas). */
  record Unaccent() implements Transformation {
    @Override
    public Object apply(Object value, MappingContext ctx) {
      if (value == null) return null;
      String n = Normalizer.normalize(value.toString(), Normalizer.Form.NFD);
      return n.replaceAll("\\p{M}+", "");
    }
  }

  /** Mantém somente dígitos (CPF, CNS, telefone). */
  record Digits() implements Transformation {
    @Override
    public Object apply(Object value, MappingContext ctx) {
      return value == null ? null : value.toString().replaceAll("\\D", "");
    }
  }

  /** Converte vazio em nulo. */
  record BlankToNull() implements Transformation {
    @Override
    public Object apply(Object value, MappingContext ctx) {
      return value == null || value.toString().isBlank() ? null : value;
    }
  }

  /**
   * Reformata data: {@code from} (padrões java.time separados por {@code |}, ex.: {@code dd/MM/yyyy
   * HH:mm:ss|dd/MM/yyyy}) → {@code to} (padrão {@code yyyy-MM-dd}). Com {@code to = iso-datetime}
   * produz ISO-8601 com offset usando {@code zone} (padrão America/Sao_Paulo); uma data sem hora
   * vira meia-noite local. Valores já em ISO-8601 com offset são aceitos como estão.
   */
  record DateFormat(String from, String to, String zone) implements Transformation {
    @Override
    public Object apply(Object value, MappingContext ctx) {
      if (value == null || value.toString().isBlank()) return null;
      String text = value.toString().trim();
      boolean iso = "iso-datetime".equals(to);
      if (iso && text.length() >= 20 && text.charAt(10) == 'T') {
        try {
          return OffsetDateTime.parse(text).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException ignored) {
          // segue para os padrões declarados
        }
      }
      ZoneId z = ZoneId.of(zone == null ? "America/Sao_Paulo" : zone);
      for (String pattern : from.split("\\|")) {
        DateTimeFormatter in = DateTimeFormatter.ofPattern(pattern.trim());
        try {
          LocalDateTime ldt;
          if (pattern.contains("H")) {
            ldt = LocalDateTime.parse(text, in);
          } else {
            ldt = LocalDate.parse(text, in).atStartOfDay();
          }
          if (iso) {
            return OffsetDateTime.of(ldt, z.getRules().getOffset(ldt))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
          }
          return ldt.toLocalDate()
              .format(DateTimeFormatter.ofPattern(to == null ? "yyyy-MM-dd" : to));
        } catch (DateTimeParseException e) {
          // tenta o próximo padrão
        }
      }
      throw new MappingException("data inválida '" + text + "' para padrão " + from, null);
    }
  }

  /** Competência: {@code from} (ex.: {@code MM/yyyy}) → {@code to} (padrão {@code yyyyMM}). */
  record YearMonthFormat(String from, String to) implements Transformation {
    @Override
    public Object apply(Object value, MappingContext ctx) {
      if (value == null || value.toString().isBlank()) return null;
      String text = value.toString().trim();
      for (String pattern : from.split("\\|")) {
        try {
          return YearMonth.parse(text, DateTimeFormatter.ofPattern(pattern.trim()))
              .format(DateTimeFormatter.ofPattern(to == null ? "yyyyMM" : to));
        } catch (DateTimeParseException e) {
          // tenta o próximo padrão
        }
      }
      throw new MappingException("competência inválida '" + text + "' para padrão " + from, null);
    }
  }

  /** Substituição por expressão regular ({@code replaceAll}). */
  record RegexReplace(Pattern pattern, String replacement) implements Transformation {
    @Override
    public Object apply(Object value, MappingContext ctx) {
      return value == null ? null : pattern.matcher(value.toString()).replaceAll(replacement);
    }
  }

  /** Converte para decimal (vírgula ou ponto); nulo se vazio. */
  record ToDecimal() implements Transformation {
    @Override
    public Object apply(Object v, MappingContext ctx) {
      if (v == null || v.toString().isBlank()) return null;
      if (v instanceof Number n) return new BigDecimal(n.toString());
      try {
        return new BigDecimal(v.toString().trim().replace(".", "").replace(',', '.'))
            .stripTrailingZeros();
      } catch (NumberFormatException e) {
        try {
          return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e2) {
          throw new MappingException("decimal inválido '" + v + "'", e2);
        }
      }
    }
  }

  /** Lookup em tabela de códigos declarada no YAML ({@code lookups.<table>}). */
  record Lookup(String table, String defaultValue, boolean strict) implements Transformation {
    @Override
    public Object apply(Object value, MappingContext ctx) {
      if (value == null) return defaultValue;
      Map<String, String> t = ctx.lookup(table);
      String key = value.toString().trim();
      if (t.containsKey(key)) return t.get(key);
      if (strict) {
        throw new MappingException("código '" + key + "' não encontrado na tabela " + table, null);
      }
      return defaultValue;
    }
  }

  /** Valor padrão quando nulo/vazio. */
  record Default(String value) implements Transformation {
    @Override
    public Object apply(Object v, MappingContext ctx) {
      return v == null || v.toString().isBlank() ? value : v;
    }
  }

  /** Substring [start, end) com limites tolerantes. */
  record Substring(int start, int end) implements Transformation {
    @Override
    public Object apply(Object v, MappingContext ctx) {
      if (v == null) return null;
      String s = v.toString();
      int e = end < 0 || end > s.length() ? s.length() : end;
      return start >= s.length() ? "" : s.substring(start, e);
    }
  }

  /** Converte para boolean a partir de lista de valores verdadeiros. */
  record ToBoolean(java.util.List<String> trueValues) implements Transformation {
    @Override
    public Object apply(Object v, MappingContext ctx) {
      if (v == null) return null;
      return trueValues.contains(v.toString().trim().toUpperCase(Locale.ROOT));
    }
  }

  /** Converte para inteiro (nulo se vazio). */
  record ToInteger() implements Transformation {
    @Override
    public Object apply(Object v, MappingContext ctx) {
      if (v == null || v.toString().isBlank()) return null;
      try {
        return Integer.parseInt(v.toString().trim());
      } catch (NumberFormatException e) {
        throw new MappingException("inteiro inválido '" + v + "'", e);
      }
    }
  }
}
