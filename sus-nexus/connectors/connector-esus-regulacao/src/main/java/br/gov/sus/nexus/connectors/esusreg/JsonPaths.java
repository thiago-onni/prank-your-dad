package br.gov.sus.nexus.connectors.esusreg;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Navegação em JSON por caminho {@code a.b[0].c} (mesma notação do MappingEngine). */
public final class JsonPaths {

  private static final Pattern INDEXED = Pattern.compile("^([A-Za-z0-9_\\-]+)\\[(\\d+)]$");

  private JsonPaths() {}

  public static JsonNode at(JsonNode root, String path) {
    if (root == null || path == null || path.isBlank()) return root;
    JsonNode current = root;
    for (String part : path.split("\\.")) {
      if (current == null || current.isMissingNode() || current.isNull()) return null;
      Matcher m = INDEXED.matcher(part);
      if (m.matches()) {
        current = current.path(m.group(1)).path(Integer.parseInt(m.group(2)));
      } else {
        current = current.path(part);
      }
    }
    return current == null || current.isMissingNode() ? null : current;
  }

  public static String text(JsonNode root, String path) {
    JsonNode n = at(root, path);
    return n == null || n.isNull() ? null : n.asText();
  }
}
