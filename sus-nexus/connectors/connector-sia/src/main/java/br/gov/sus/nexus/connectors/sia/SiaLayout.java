package br.gov.sus.nexus.connectors.sia;

import br.gov.sus.nexus.connectors.sdk.parse.DelimitedParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Layout versionado (YAML) dos arquivos do conector SIA/SIH. Cada {@link Kind} declara o grupo
 * (pasta) de origem, o padrão do nome do arquivo, o formato ({@code delimited} com cabeçalho e
 * aliases normalizados, como no SISREG; {@code fixed-width} com posições 1-based inclusivas; ou
 * {@code dbf} — tabela dBase dos arquivos de disseminação do DATASUS, {@code .dbf} ou {@code .dbc},
 * com os nomes de campo resolvidos por aliases como no {@code delimited}), o {@code source_system},
 * a entidade canônica e, para retornos, o status ({@code oficial} quando confirmado em fonte
 * oficial do DATASUS; {@code A CONFIRMAR} enquanto não confirmado nem homologado com o
 * faturamento).
 *
 * <pre>
 * version: "1.0.0"
 * kinds:
 *   - name: producao
 *     group: producao                # producao | retorno
 *     entity: production_record      # production_record | production_outcome
 *     file_pattern: "(?i).*producao.*\\.csv"
 *     format: delimited              # delimited | fixed-width | dbf
 *     delimiter: ";"
 *     charset: UTF-8
 *     source_system: ORIGEM
 *     id_column: id_registro
 *     version_column: data_atualizacao
 *     defaults: { quantidade: "1" }
 *     columns:
 *       id_registro: [id_registro, codigo, id]          # delimited: aliases (coalesce)
 *   - name: retorno_local_txt
 *     format: fixed-width
 *     line_filter: { start: 1, end: 2, equals: "02" } # só linhas de detalhe
 *     columns:
 *       numero_aih: { start: 3, end: 15 }              # fixed-width: posições
 *   - name: retorno_sih_rj
 *     format: dbf                                       # RJ<UF><AAMM>.dbc/.dbf (DATASUS)
 *     row_filter: { column: situacao, in: ["1"] }      # filtro por coluna canônica
 * </pre>
 */
public final class SiaLayout {

  public static final String GROUP_PRODUCTION = "producao";
  public static final String GROUP_RETURN = "retorno";

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private final String version;
  private final List<Kind> kinds;

  public SiaLayout(String version, List<Kind> kinds) {
    this.version = version;
    this.kinds = List.copyOf(kinds);
  }

  /** Posição em arquivo de largura fixa (1-based, inclusiva). */
  public record Position(int start, int end) {
    String slice(String line) {
      if (line.length() < start) return "";
      return line.substring(start - 1, Math.min(end, line.length())).trim();
    }
  }

  /**
   * Filtro de linha por coluna canônica (qualquer formato): {@code in} = valores aceitos; {@code
   * not_in} = valores recusados (comparação após trim). Linhas recusadas não viram mensagem, mas
   * mantêm a numeração (idempotência por arquivo + linha).
   */
  public record RowFilter(String column, List<String> in, List<String> notIn) {
    public RowFilter {
      in = in == null ? List.of() : List.copyOf(in);
      notIn = notIn == null ? List.of() : List.copyOf(notIn);
    }

    boolean accepts(Map<String, String> canonical) {
      String v = canonical.getOrDefault(column, "");
      v = v == null ? "" : v.trim();
      if (!in.isEmpty() && !in.contains(v)) return false;
      return !notIn.contains(v);
    }
  }

  /** Filtro de linha em largura fixa (ex.: tipo de registro "02" = detalhe). */
  public record LineFilter(Position position, String equalsValue) {
    boolean accepts(String line) {
      return position.slice(line).equals(equalsValue);
    }
  }

  /** Um tipo de arquivo. */
  public record Kind(
      String name,
      String group,
      String entity,
      String status,
      Pattern filePattern,
      String format,
      char delimiter,
      String charset,
      String sourceSystem,
      String idColumn,
      String versionColumn,
      LineFilter lineFilter,
      List<RowFilter> rowFilters,
      Map<String, String> defaults,
      Map<String, List<String>> aliases,
      Map<String, Position> positions) {

    public Kind {
      rowFilters = rowFilters == null ? List.of() : List.copyOf(rowFilters);
      defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
      aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
      positions = positions == null ? Map.of() : Map.copyOf(positions);
    }

    public boolean matches(String fileName) {
      return filePattern.matcher(fileName).matches();
    }

    public boolean fixedWidth() {
      return "fixed-width".equalsIgnoreCase(format);
    }

    /** Tabela dBase ({@code .dbf}) ou DBC do DATASUS (descomprimido antes da leitura). */
    public boolean dbf() {
      return "dbf".equalsIgnoreCase(format);
    }

    /** A linha canônica passa nos {@code row_filter} do kind. */
    public boolean accepts(Map<String, String> canonical) {
      for (RowFilter f : rowFilters) {
        if (!f.accepts(canonical)) return false;
      }
      return true;
    }

    /** Layout sem confirmação em fonte oficial nem homologação local: status "A CONFIRMAR". */
    public boolean pendingConfirmation() {
      return status != null && status.toUpperCase(Locale.ROOT).contains("CONFIRMAR");
    }

    /**
     * Lê o arquivo e devolve as linhas de dados com os campos de origem (cabeçalho original →
     * valor, ou coluna canônica → valor em largura fixa), na ordem do arquivo.
     */
    public List<Map<String, String>> read(byte[] content, Charset fallback) {
      Charset cs = charset == null ? fallback : Charset.forName(charset);
      if (dbf()) {
        return DbfReader.read(content, cs).rows();
      }
      if (!fixedWidth()) {
        return new DelimitedParser(delimiter, true, List.of()).parse(content, cs);
      }
      List<Map<String, String>> rows = new ArrayList<>();
      String text = new String(content, cs);
      if (text.startsWith("﻿")) text = text.substring(1);
      try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) continue;
          if (lineFilter != null && !lineFilter.accepts(line)) continue;
          String current = line;
          Map<String, String> row = new LinkedHashMap<>();
          positions.forEach((col, pos) -> row.put(col, pos.slice(current)));
          rows.add(row);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return rows;
    }

    /** Converte campos de origem em colunas canônicas (aliases + defaults). */
    public Map<String, String> canonicalize(Map<String, String> row) {
      Map<String, String> out = new LinkedHashMap<>();
      if (fixedWidth()) {
        positions.keySet().forEach(k -> out.put(k, nz(row.get(k))));
      } else {
        Map<String, String> normalized = new LinkedHashMap<>();
        row.forEach((k, v) -> normalized.put(normalize(k), v == null ? "" : v.trim()));
        for (Map.Entry<String, List<String>> e : aliases.entrySet()) {
          String value = "";
          for (String alias : e.getValue()) {
            String v = normalized.get(normalize(alias));
            if (v != null && !v.isBlank()) {
              value = v;
              break;
            }
          }
          out.put(e.getKey(), value);
        }
      }
      defaults.forEach(
          (k, v) -> {
            if (out.getOrDefault(k, "").isBlank()) out.put(k, v);
          });
      return out;
    }

    private static String nz(String v) {
      return v == null ? "" : v.trim();
    }
  }

  public static SiaLayout load(String location) {
    try {
      if (location.startsWith("classpath:")) {
        String res = location.substring("classpath:".length());
        try (InputStream in = SiaLayout.class.getClassLoader().getResourceAsStream(res)) {
          if (in == null) throw new IllegalArgumentException("layout não encontrado: " + res);
          return parse(YAML.readTree(in));
        }
      }
      return parse(YAML.readTree(Files.readString(Path.of(location))));
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao ler layout SIA em " + location, e);
    }
  }

  static SiaLayout parse(JsonNode root) {
    List<Kind> kinds = new ArrayList<>();
    for (JsonNode k : root.withArray("kinds")) {
      String name = k.get("name").asText();
      String group = k.path("group").asText(GROUP_PRODUCTION);
      if (!GROUP_PRODUCTION.equals(group) && !GROUP_RETURN.equals(group)) {
        throw new IllegalArgumentException("kind " + name + ": group inválido " + group);
      }
      Map<String, String> defaults = new LinkedHashMap<>();
      k.path("defaults")
          .fields()
          .forEachRemaining(e -> defaults.put(e.getKey(), e.getValue().asText()));
      Map<String, List<String>> aliases = new LinkedHashMap<>();
      Map<String, Position> positions = new LinkedHashMap<>();
      k.path("columns")
          .fields()
          .forEachRemaining(
              e -> {
                JsonNode v = e.getValue();
                if (v.isObject()) {
                  positions.put(
                      e.getKey(), new Position(v.path("start").asInt(1), v.path("end").asInt(1)));
                } else {
                  List<String> list = new ArrayList<>();
                  if (v.isArray()) v.forEach(a -> list.add(a.asText()));
                  else list.add(v.asText());
                  aliases.put(e.getKey(), list);
                }
              });
      String format = k.path("format").asText("delimited");
      if ("fixed-width".equalsIgnoreCase(format) && positions.isEmpty()) {
        throw new IllegalArgumentException("kind " + name + ": fixed-width exige posições");
      }
      LineFilter filter = null;
      JsonNode lf = k.get("line_filter");
      if (lf != null && lf.isObject()) {
        filter =
            new LineFilter(
                new Position(lf.path("start").asInt(1), lf.path("end").asInt(1)),
                lf.path("equals").asText(""));
      }
      List<RowFilter> rowFilters = new ArrayList<>();
      JsonNode rf = k.get("row_filter");
      if (rf != null) {
        List<JsonNode> items = new ArrayList<>();
        if (rf.isArray()) rf.forEach(items::add);
        else items.add(rf);
        for (JsonNode f : items) {
          rowFilters.add(
              new RowFilter(f.path("column").asText(), texts(f.get("in")), texts(f.get("not_in"))));
        }
      }
      if (!List.of("delimited", "fixed-width", "dbf").contains(format.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("kind " + name + ": format inválido " + format);
      }
      String delimiter = k.path("delimiter").asText(";");
      kinds.add(
          new Kind(
              name,
              group,
              k.get("entity").asText(),
              k.path("status").asText("homologado"),
              Pattern.compile(k.get("file_pattern").asText()),
              format,
              delimiter.isEmpty() ? ';' : delimiter.charAt(0),
              k.hasNonNull("charset") ? k.get("charset").asText() : null,
              k.path("source_system").asText(null),
              k.path("id_column").asText(null),
              k.path("version_column").asText(null),
              filter,
              rowFilters,
              defaults,
              aliases,
              positions));
    }
    return new SiaLayout(root.path("version").asText("1.0.0"), kinds);
  }

  private static List<String> texts(JsonNode node) {
    List<String> out = new ArrayList<>();
    if (node == null || node.isNull()) return out;
    if (node.isArray()) node.forEach(v -> out.add(v.asText().trim()));
    else out.add(node.asText().trim());
    return out;
  }

  public String version() {
    return version;
  }

  public List<Kind> kinds() {
    return kinds;
  }

  /** Primeiro kind do grupo cujo padrão casa com o nome do arquivo. */
  public Optional<Kind> forFile(String group, String fileName) {
    return kinds.stream().filter(k -> k.group().equals(group) && k.matches(fileName)).findFirst();
  }

  public Kind require(String name) {
    return kinds.stream()
        .filter(k -> k.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("kind de layout desconhecido: " + name));
  }

  /** Normaliza um cabeçalho: minúsculas, sem acentos, não alfanuméricos → {@code _}. */
  public static String normalize(String header) {
    if (header == null) return "";
    String n = Normalizer.normalize(header, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    return n.replaceAll("^_+|_+$", "");
  }
}
