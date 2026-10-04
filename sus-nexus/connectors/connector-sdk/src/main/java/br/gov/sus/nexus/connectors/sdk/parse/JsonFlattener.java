package br.gov.sus.nexus.connectors.sdk.parse;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Achata um JSON em um mapa plano cujas chaves usam a mesma notação de caminho do {@link
 * br.gov.sus.nexus.connectors.sdk.mapping.MappingEngine} ({@code a.b[0].c}), permitindo mapear
 * respostas de APIs JSON com YAML declarativo ("jsonpath-like"). Valores escalares viram texto
 * (números sem notação científica, booleanos como {@code true}/{@code false}); nulos são omitidos.
 */
public final class JsonFlattener {

  private JsonFlattener() {}

  public static Map<String, String> flatten(JsonNode node) {
    Map<String, String> out = new LinkedHashMap<>();
    walk("", node, out);
    return out;
  }

  private static void walk(String prefix, JsonNode node, Map<String, String> out) {
    if (node == null || node.isNull() || node.isMissingNode()) return;
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        walk(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        walk(prefix + "[" + i + "]", node.get(i), out);
      }
      out.put(prefix + ".length", String.valueOf(node.size()));
    } else if (node.isNumber()) {
      out.put(prefix, node.decimalValue().stripTrailingZeros().toPlainString());
    } else {
      out.put(prefix, node.asText());
    }
  }
}
