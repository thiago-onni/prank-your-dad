package br.gov.sus.nexus.connectors.sdk.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Carrega layouts de tabela de um YAML ({@code classpath:} ou caminho de arquivo):
 *
 * <pre>
 * version: "1.0.0"
 * tables:
 *   - name: tb_procedimento
 *     system: SIGTAP
 *     file_pattern: "(?i)tb_procedimento.*\\.txt"
 *     format: fixed-width
 *     charset: ISO-8859-1
 *     columns:
 *       - { name: CO_PROCEDIMENTO, start: 1, end: 10 }
 *     fields: { code: CO_PROCEDIMENTO, display: NO_PROCEDIMENTO, competence: DT_COMPETENCIA }
 *     attributes: [TP_COMPLEXIDADE]
 *   - name: cid10
 *     system: CID10
 *     file_pattern: "(?i)cid10.*\\.csv"
 *     format: delimited
 *     delimiter: ";"
 *     header: true
 *     fields: { code: codigo, display: descricao }
 * </pre>
 */
public final class LayoutRegistry {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private final String version;
  private final List<TableLayout> layouts;

  public LayoutRegistry(String version, List<TableLayout> layouts) {
    this.version = version;
    this.layouts = List.copyOf(layouts);
  }

  public static LayoutRegistry load(String location) {
    try {
      if (location.startsWith("classpath:")) {
        String res = location.substring("classpath:".length());
        try (InputStream in = LayoutRegistry.class.getClassLoader().getResourceAsStream(res)) {
          if (in == null)
            throw new IllegalArgumentException("layout não encontrado no classpath: " + res);
          return parse(YAML.readTree(in));
        }
      }
      return parse(YAML.readTree(Files.readString(Path.of(location))));
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao ler layouts em " + location, e);
    }
  }

  static LayoutRegistry parse(JsonNode root) {
    String version = root.path("version").asText("1.0.0");
    List<TableLayout> out = new ArrayList<>();
    for (JsonNode t : root.withArray("tables")) {
      String format = t.path("format").asText("delimited");
      List<FixedWidthParser.Column> columns = new ArrayList<>();
      for (JsonNode c : t.withArray("columns")) {
        if (c.isTextual()) {
          columns.add(new FixedWidthParser.Column(c.asText(), 1, 1));
        } else {
          columns.add(
              new FixedWidthParser.Column(
                  c.get("name").asText(), c.path("start").asInt(1), c.path("end").asInt(1)));
        }
      }
      Map<String, String> fields = new LinkedHashMap<>();
      t.path("fields")
          .fields()
          .forEachRemaining(e -> fields.put(e.getKey(), e.getValue().asText()));
      List<String> attributes = new ArrayList<>();
      t.withArray("attributes").forEach(a -> attributes.add(a.asText()));
      String delimiter = t.path("delimiter").asText(";");
      out.add(
          new TableLayout(
              t.get("name").asText(),
              t.path("system").asText(null),
              Pattern.compile(t.get("file_pattern").asText()),
              "fixed-width".equalsIgnoreCase(format)
                  ? TableLayout.Format.FIXED_WIDTH
                  : TableLayout.Format.DELIMITED,
              Charset.forName(t.path("charset").asText("UTF-8")),
              delimiter.isEmpty() ? ';' : delimiter.charAt(0),
              t.path("header").asBoolean(true),
              columns,
              fields,
              attributes));
    }
    return new LayoutRegistry(version, out);
  }

  public String version() {
    return version;
  }

  public List<TableLayout> layouts() {
    return layouts;
  }

  public Optional<TableLayout> forFile(String fileName) {
    return layouts.stream().filter(l -> l.matches(fileName)).findFirst();
  }

  public Optional<TableLayout> byName(String name) {
    return layouts.stream().filter(l -> l.name().equals(name)).findFirst();
  }
}
