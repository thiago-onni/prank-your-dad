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
 * Parser de texto delimitado (CSV com {@code ;} ou {@code ,}) com suporte a aspas duplas e
 * cabeçalho. Sem cabeçalho, os nomes vêm de {@code columns}. Linhas vazias são ignoradas.
 */
public final class DelimitedParser {

  private final char delimiter;
  private final boolean header;
  private final List<String> columns;

  public DelimitedParser(char delimiter, boolean header, List<String> columns) {
    this.delimiter = delimiter;
    this.header = header;
    this.columns = columns == null ? List.of() : List.copyOf(columns);
  }

  public static DelimitedParser semicolonWithHeader() {
    return new DelimitedParser(';', true, List.of());
  }

  public List<Map<String, String>> parse(byte[] content, Charset charset) {
    return parse(new String(content, charset));
  }

  public List<Map<String, String>> parse(String text) {
    List<Map<String, String>> rows = new ArrayList<>();
    List<String> names = new ArrayList<>(columns);
    try (BufferedReader reader = new BufferedReader(new StringReader(stripBom(text)))) {
      String line;
      boolean first = true;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        List<String> fields = splitLine(line);
        if (first && header) {
          names = fields.stream().map(String::trim).toList();
          first = false;
          continue;
        }
        first = false;
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
          String name = i < names.size() ? names.get(i) : "col" + i;
          row.put(name, fields.get(i));
        }
        rows.add(row);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return rows;
  }

  /** Divide uma linha respeitando aspas duplas (e aspas escapadas por duplicação). */
  List<String> splitLine(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cur.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == delimiter) {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    out.add(cur.toString());
    return out;
  }

  private static String stripBom(String text) {
    return text.startsWith("﻿") ? text.substring(1) : text;
  }
}
