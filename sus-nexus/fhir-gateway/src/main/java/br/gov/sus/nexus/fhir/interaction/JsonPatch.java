package br.gov.sus.nexus.fhir.interaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;

/**
 * Aplicação de JSON Patch (RFC 6902: add, remove, replace, move, copy, test) sobre uma árvore
 * Jackson. Falhas de operação viram 422 ({@code processing}); documento de patch malformado vira
 * 400.
 */
public final class JsonPatch {

  private JsonPatch() {}

  /** Aplica o patch a uma cópia de {@code target} e devolve o resultado. */
  public static JsonNode apply(JsonNode patch, JsonNode target) {
    if (patch == null || !patch.isArray()) {
      throw FhirException.invalid("JSON Patch deve ser um array de operações");
    }
    JsonNode doc = target.deepCopy();
    int i = 0;
    for (JsonNode op : patch) {
      String name = text(op, "op", i);
      String path = text(op, "path", i);
      switch (name) {
        case "add" -> doc = add(doc, path, value(op, i));
        case "remove" -> doc = remove(doc, path);
        case "replace" -> {
          get(doc, path);
          doc = add(remove(doc, path), path, value(op, i));
        }
        case "move" -> {
          String from = text(op, "from", i);
          JsonNode v = get(doc, from).deepCopy();
          doc = add(remove(doc, from), path, v);
        }
        case "copy" -> doc = add(doc, path, get(doc, text(op, "from", i)).deepCopy());
        case "test" -> {
          if (!get(doc, path).equals(value(op, i))) {
            throw failed("test falhou em " + path);
          }
        }
        default -> throw FhirException.invalid("Operação JSON Patch desconhecida: " + name);
      }
      i++;
    }
    return doc;
  }

  private static String text(JsonNode op, String field, int i) {
    JsonNode v = op.get(field);
    if (v == null || !v.isTextual()) {
      throw FhirException.invalid("JSON Patch[" + i + "] sem '" + field + "'");
    }
    return v.asText();
  }

  private static JsonNode value(JsonNode op, int i) {
    if (!op.has("value")) {
      throw FhirException.invalid("JSON Patch[" + i + "] sem 'value'");
    }
    return op.get("value");
  }

  private static List<String> tokens(String path) {
    if (path.isEmpty()) {
      return List.of();
    }
    if (!path.startsWith("/")) {
      throw FhirException.invalid("JSON Pointer inválido: " + path);
    }
    List<String> out = new ArrayList<>();
    for (String t : path.substring(1).split("/", -1)) {
      out.add(t.replace("~1", "/").replace("~0", "~"));
    }
    return out;
  }

  private static JsonNode get(JsonNode doc, String path) {
    JsonNode cur = doc;
    for (String t : tokens(path)) {
      cur = child(cur, t);
      if (cur == null) {
        throw failed("caminho inexistente: " + path);
      }
    }
    return cur;
  }

  private static JsonNode child(JsonNode node, String token) {
    if (node.isObject()) {
      return node.get(token);
    }
    if (node.isArray()) {
      int idx = index(token, node.size() - 1);
      return node.get(idx);
    }
    return null;
  }

  private static int index(String token, int max) {
    try {
      int idx = Integer.parseInt(token);
      if (idx < 0 || idx > max) {
        throw failed("índice fora do intervalo: " + token);
      }
      return idx;
    } catch (NumberFormatException e) {
      throw failed("índice de array inválido: " + token);
    }
  }

  private static JsonNode add(JsonNode doc, String path, JsonNode value) {
    List<String> t = tokens(path);
    if (t.isEmpty()) {
      return value;
    }
    JsonNode parent = get(doc, parentPath(t));
    String last = t.get(t.size() - 1);
    if (parent instanceof ObjectNode obj) {
      obj.set(last, value);
    } else if (parent instanceof ArrayNode arr) {
      if ("-".equals(last)) {
        arr.add(value);
      } else {
        arr.insert(index(last, arr.size()), value);
      }
    } else {
      throw failed("destino não é objeto nem array: " + path);
    }
    return doc;
  }

  private static JsonNode remove(JsonNode doc, String path) {
    List<String> t = tokens(path);
    if (t.isEmpty()) {
      throw failed("não é possível remover a raiz");
    }
    JsonNode parent = get(doc, parentPath(t));
    String last = t.get(t.size() - 1);
    if (parent instanceof ObjectNode obj) {
      if (obj.remove(last) == null) {
        throw failed("caminho inexistente: " + path);
      }
    } else if (parent instanceof ArrayNode arr) {
      arr.remove(index(last, arr.size() - 1));
    } else {
      throw failed("caminho inexistente: " + path);
    }
    return doc;
  }

  private static String parentPath(List<String> tokens) {
    StringBuilder sb = new StringBuilder();
    for (String s : tokens.subList(0, tokens.size() - 1)) {
      sb.append('/').append(s.replace("~", "~0").replace("/", "~1"));
    }
    return sb.toString();
  }

  private static FhirException failed(String message) {
    return new FhirException(422, IssueType.PROCESSING, "JSON Patch não aplicável: " + message);
  }
}
