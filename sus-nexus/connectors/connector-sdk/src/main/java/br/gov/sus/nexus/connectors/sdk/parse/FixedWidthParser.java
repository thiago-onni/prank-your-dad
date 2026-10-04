package br.gov.sus.nexus.connectors.sdk.parse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser de largura fixa (layout SIGTAP). Posições são 1-based e inclusivas, como nos arquivos
 * {@code *_layout.txt} do DATASUS. Valores são aparados ({@code trim}).
 */
public final class FixedWidthParser {

  public record Column(String name, int start, int end) {
    public Column {
      if (start < 1 || end < start) {
        throw new IllegalArgumentException(
            "coluna " + name + ": posições inválidas " + start + "-" + end);
      }
    }
  }

  private final List<Column> columns;

  public FixedWidthParser(List<Column> columns) {
    this.columns = List.copyOf(columns);
  }

  public List<Map<String, String>> parse(byte[] content, Charset charset) {
    return parse(new String(content, charset));
  }

  public List<Map<String, String>> parse(String text) {
    List<Map<String, String>> rows = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        rows.add(parseLine(line));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return rows;
  }

  public Map<String, String> parseLine(String line) {
    Map<String, String> row = new LinkedHashMap<>();
    for (Column c : columns) {
      int from = Math.min(c.start() - 1, line.length());
      int to = Math.min(c.end(), line.length());
      row.put(c.name(), line.substring(from, to).trim());
    }
    return row;
  }
}
