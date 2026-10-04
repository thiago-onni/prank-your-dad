package br.gov.sus.nexus.connectors.sdk.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carrega mapeamentos YAML do classpath. Formato:
 *
 * <pre>
 * mapping_set: pec-citizen
 * version: "1.0.0"
 * entity_type: citizen
 * lookups:
 *   sexo: { M: male, F: female }
 * fields:
 *   - { source: no_cidadao, target: demographics.legal_name, transforms: [trim, upper], required: true }
 *   - { source: dt_nascimento, target: demographics.birthdate, transforms: [ { date: { from: ddMMyyyy } } ] }
 *   - { source: sexo, target: demographics.sex, transforms: [ { lookup: { table: sexo, default: unknown } } ] }
 *   - { target: source.system, constant: PEC }
 *   - { target: "identifiers[0].system", constant: CPF, depends_on: nu_cpf }
 * </pre>
 */
public final class MappingLoader {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private MappingLoader() {}

  public static MappingVersion fromClasspath(String resource) {
    try (InputStream in = MappingLoader.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) throw new IllegalArgumentException("mapeamento não encontrado: " + resource);
      return parse(YAML.readTree(in));
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao ler mapeamento " + resource, e);
    }
  }

  public static MappingVersion fromYaml(String yaml) {
    try {
      return parse(YAML.readTree(yaml));
    } catch (IOException e) {
      throw new UncheckedIOException("YAML de mapeamento inválido", e);
    }
  }

  public static MappingSet setFromClasspath(String name, String... resources) {
    List<MappingVersion> versions = new ArrayList<>();
    for (String r : resources) versions.add(fromClasspath(r));
    return new MappingSet(name, versions);
  }

  static MappingVersion parse(JsonNode root) {
    String set = required(root, "mapping_set");
    String version = required(root, "version");
    String entity = required(root, "entity_type");
    Map<String, Map<String, String>> lookups = new LinkedHashMap<>();
    JsonNode l = root.get("lookups");
    if (l != null) {
      l.fields()
          .forEachRemaining(
              e -> {
                Map<String, String> table = new LinkedHashMap<>();
                e.getValue()
                    .fields()
                    .forEachRemaining(kv -> table.put(kv.getKey(), kv.getValue().asText()));
                lookups.put(e.getKey(), table);
              });
    }
    List<FieldMapping> fields = new ArrayList<>();
    JsonNode f = root.get("fields");
    if (f == null || !f.isArray()) throw new IllegalArgumentException("mapeamento sem 'fields'");
    for (JsonNode node : f) {
      fields.add(
          new FieldMapping(
              text(node, "source"),
              text(node, "target"),
              text(node, "constant"),
              node.path("required").asBoolean(false),
              text(node, "depends_on"),
              parseTransforms(node.get("transforms"))));
    }
    return new MappingVersion(set, version, entity, fields, lookups);
  }

  private static List<Transformation> parseTransforms(JsonNode node) {
    List<Transformation> out = new ArrayList<>();
    if (node == null || node.isNull()) return out;
    for (JsonNode t : node) {
      if (t.isTextual()) {
        out.add(simple(t.asText()));
      } else if (t.isObject()) {
        String kind = t.fieldNames().next();
        JsonNode cfg = t.get(kind);
        out.add(configured(kind, cfg));
      } else {
        throw new IllegalArgumentException("transformação inválida: " + t);
      }
    }
    return out;
  }

  private static Transformation simple(String name) {
    return switch (name) {
      case "trim" -> new Transformation.Trim();
      case "upper" -> new Transformation.Upper();
      case "lower" -> new Transformation.Lower();
      case "unaccent" -> new Transformation.Unaccent();
      case "digits" -> new Transformation.Digits();
      case "blank_to_null" -> new Transformation.BlankToNull();
      case "to_integer" -> new Transformation.ToInteger();
      default -> throw new IllegalArgumentException("transformação desconhecida: " + name);
    };
  }

  private static Transformation configured(String kind, JsonNode cfg) {
    return switch (kind) {
      case "date" ->
          new Transformation.DateFormat(required(cfg, "from"), text(cfg, "to"), text(cfg, "zone"));
      case "lookup" ->
          new Transformation.Lookup(
              required(cfg, "table"), text(cfg, "default"), cfg.path("strict").asBoolean(false));
      case "default" -> new Transformation.Default(cfg.asText());
      case "substring" ->
          new Transformation.Substring(cfg.path("start").asInt(0), cfg.path("end").asInt(-1));
      case "to_boolean" -> {
        List<String> trues = new ArrayList<>();
        JsonNode tv = cfg.get("true_values");
        if (tv != null) tv.forEach(n -> trues.add(n.asText().toUpperCase()));
        if (trues.isEmpty()) trues.addAll(List.of("1", "S", "SIM", "TRUE", "Y"));
        yield new Transformation.ToBoolean(trues);
      }
      default -> throw new IllegalArgumentException("transformação desconhecida: " + kind);
    };
  }

  private static String required(JsonNode node, String field) {
    String v = text(node, field);
    if (v == null)
      throw new IllegalArgumentException("campo obrigatório ausente no YAML: " + field);
    return v;
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }
}
