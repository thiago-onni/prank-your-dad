package br.gov.sus.nexus.fhir.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Verificação de parse estrito. O {@code JsonParser} de referência ignora propriedades
 * desconhecidas e tolera formas erradas (ex.: array onde se espera valor, string onde se espera
 * boolean). Esta classe compara a árvore JSON de entrada com a árvore re-serializada após o parse:
 * qualquer propriedade/valor da entrada que não sobreviva idêntico é conteúdo desconhecido ou
 * inválido.
 */
final class StrictContentChecker {

  private StrictContentChecker() {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Devolve o caminho do primeiro problema encontrado, se houver. */
  static Optional<String> firstMismatch(String inputJson, String outputJson) {
    try {
      JsonNode in = MAPPER.readTree(inputJson);
      JsonNode out = MAPPER.readTree(outputJson);
      String root = in.path("resourceType").asText("Resource");
      return Optional.ofNullable(diff(in, out, root));
    } catch (IOException e) {
      return Optional.of("$");
    }
  }

  private static String diff(JsonNode in, JsonNode out, String path) {
    if (out == null || out.isMissingNode()) {
      return path;
    }
    if (in.isObject()) {
      if (!out.isObject()) {
        return path;
      }
      Iterator<Map.Entry<String, JsonNode>> fields = in.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> f = fields.next();
        String childPath = path + "." + f.getKey();
        JsonNode o = out.get(f.getKey());
        if (o == null) {
          return childPath;
        }
        String d = diff(f.getValue(), o, childPath);
        if (d != null) {
          return d;
        }
      }
      return null;
    }
    if (in.isArray()) {
      if (!out.isArray() || out.size() != in.size()) {
        return path;
      }
      for (int i = 0; i < in.size(); i++) {
        String d = diff(in.get(i), out.get(i), path + "[" + i + "]");
        if (d != null) {
          return d;
        }
      }
      return null;
    }
    if (in.isNull()) {
      return path;
    }
    if (in.isNumber()) {
      return out.isNumber() && in.decimalValue().compareTo(out.decimalValue()) == 0 ? null : path;
    }
    if (in.isBoolean()) {
      return out.isBoolean() && in.booleanValue() == out.booleanValue() ? null : path;
    }
    if (in.isTextual()) {
      return out.isTextual() && in.textValue().equals(out.textValue()) ? null : path;
    }
    return path;
  }
}
