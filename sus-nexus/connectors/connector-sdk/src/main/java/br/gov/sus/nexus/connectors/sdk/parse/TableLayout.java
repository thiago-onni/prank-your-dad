package br.gov.sus.nexus.connectors.sdk.parse;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Layout declarativo de uma tabela em arquivo (largura fixa ou delimitado), carregado de YAML por
 * {@link LayoutRegistry}. {@code fields} são campos semânticos (ex.: {@code code}, {@code display},
 * {@code competence}) resolvidos para colunas do arquivo.
 */
public record TableLayout(
    String name,
    String system,
    Pattern filePattern,
    Format format,
    Charset charset,
    char delimiter,
    boolean header,
    List<FixedWidthParser.Column> columns,
    Map<String, String> fields,
    List<String> attributeColumns) {

  public enum Format {
    FIXED_WIDTH,
    DELIMITED
  }

  public TableLayout {
    columns = columns == null ? List.of() : List.copyOf(columns);
    fields = fields == null ? Map.of() : Map.copyOf(fields);
    attributeColumns = attributeColumns == null ? List.of() : List.copyOf(attributeColumns);
  }

  public boolean matches(String fileName) {
    return filePattern.matcher(fileName).matches();
  }

  public List<Map<String, String>> parse(byte[] content) {
    return switch (format) {
      case FIXED_WIDTH -> new FixedWidthParser(columns).parse(content, charset);
      case DELIMITED ->
          new DelimitedParser(
                  delimiter, header, columns.stream().map(FixedWidthParser.Column::name).toList())
              .parse(content, charset);
    };
  }

  /** Coluna do arquivo que responde pelo campo semântico (ex.: {@code code}). */
  public String column(String field) {
    return fields.get(field);
  }

  public String value(Map<String, String> row, String field) {
    String col = column(field);
    return col == null ? null : row.get(col);
  }
}
