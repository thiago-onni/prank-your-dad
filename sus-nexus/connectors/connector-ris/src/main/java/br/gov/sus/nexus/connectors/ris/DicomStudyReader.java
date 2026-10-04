package br.gov.sus.nexus.connectors.ris;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lê metadados de estudos DICOM exportados do PACS (JSON: objeto ou array de objetos; CSV com
 * cabeçalho) em linhas planas {@code dicom.<atributo>} com nomes normalizados (minúsculos, sem
 * espaços/underscores): {@code dicom.studyinstanceuid}, {@code dicom.accessionnumber}, {@code
 * dicom.modality}, {@code dicom.studydate}, {@code dicom.studytime}, {@code dicom.seriescount},
 * {@code dicom.instancecount}, {@code dicom.aetitle}... Só metadados: nenhum pixel data, nenhum
 * nome de paciente é usado (ficam na raw zone).
 */
public final class DicomStudyReader {

  private static final ObjectMapper JSON = new ObjectMapper();

  private DicomStudyReader() {}

  public static List<Map<String, String>> read(byte[] content, String contentType, Charset csv) {
    String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    if (ct.contains("json")) return readJson(content);
    if (ct.contains("csv")) return readCsv(new String(content, csv));
    String text = new String(content, csv).stripLeading();
    return text.startsWith("{") || text.startsWith("[") ? readJson(content) : readCsv(text);
  }

  static List<Map<String, String>> readJson(byte[] content) {
    try {
      JsonNode root = JSON.readTree(content);
      List<Map<String, String>> rows = new ArrayList<>();
      if (root.isArray()) {
        root.forEach(n -> rows.add(flatten(n)));
      } else if (root.has("studies") && root.get("studies").isArray()) {
        root.get("studies").forEach(n -> rows.add(flatten(n)));
      } else if (root.isObject()) {
        rows.add(flatten(root));
      }
      return rows;
    } catch (IOException e) {
      throw new UncheckedIOException("JSON DICOM inválido", e);
    }
  }

  private static Map<String, String> flatten(JsonNode node) {
    Map<String, String> row = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            e -> {
              JsonNode v = e.getValue();
              if (v.isValueNode()) row.put(key(e.getKey()), v.asText());
              else if (v.isArray()) row.put(key(e.getKey()) + "count", String.valueOf(v.size()));
            });
    return row;
  }

  static List<Map<String, String>> readCsv(String text) {
    List<Map<String, String>> rows = new ArrayList<>();
    String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
    if (lines.length == 0 || lines[0].isBlank()) return rows;
    char sep =
        lines[0].chars().filter(c -> c == ';').count()
                > lines[0].chars().filter(c -> c == ',').count()
            ? ';'
            : ',';
    List<String> header = split(lines[0], sep);
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) continue;
      List<String> cols = split(lines[i], sep);
      Map<String, String> row = new LinkedHashMap<>();
      for (int c = 0; c < header.size() && c < cols.size(); c++) {
        row.put(key(header.get(c)), cols.get(c));
      }
      rows.add(row);
    }
    return rows;
  }

  private static List<String> split(String line, char sep) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cur.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (ch == sep && !quoted) {
        out.add(cur.toString().trim());
        cur.setLength(0);
      } else {
        cur.append(ch);
      }
    }
    out.add(cur.toString().trim());
    return out;
  }

  /**
   * {@code "Study Instance UID"}, {@code study_instance_uid}, {@code StudyInstanceUID} → mesma
   * chave.
   */
  static String key(String name) {
    String k = name == null ? "" : name.replace("﻿", "");
    k = k.replaceAll("[\\s_\\-()]", "").toLowerCase(Locale.ROOT);
    // tags DICOM em hexadecimal mais comuns
    return "dicom."
        + switch (k) {
          case "0020000d" -> "studyinstanceuid";
          case "00080050" -> "accessionnumber";
          case "00080060" -> "modality";
          case "00080020" -> "studydate";
          case "00080030" -> "studytime";
          case "00201206" -> "seriescount";
          case "00201208" -> "instancecount";
          default -> k;
        };
  }
}
