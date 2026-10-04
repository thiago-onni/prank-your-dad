package br.gov.sus.nexus.connectors.sdk.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aplica um {@link MappingVersion} a um registro de origem (mapa plano) produzindo o payload
 * canônico.
 */
public final class MappingEngine {

  private static final Pattern INDEXED = Pattern.compile("^([A-Za-z0-9_]+)\\[(\\d+)]$");

  private MappingEngine() {}

  public static Map<String, Object> apply(MappingVersion mapping, Map<String, ?> source) {
    Map<String, Object> target = new LinkedHashMap<>();
    for (FieldMapping fm : mapping.fields()) {
      if (fm.dependsOn() != null && isBlank(source.get(fm.dependsOn()))) continue;
      Object value = fm.constant() != null ? fm.constant() : source.get(fm.source());
      for (Transformation t : fm.transforms()) {
        value = t.apply(value, mapping);
      }
      if (value == null || (value instanceof String s && s.isEmpty())) {
        if (fm.required()) {
          throw new MappingException(
              "campo obrigatório vazio: " + fm.target() + " (origem " + fm.source() + ")", null);
        }
        continue;
      }
      put(target, fm.target(), value);
    }
    compactLists(target);
    return target;
  }

  /** Remove posições nulas de listas (índices pulados por {@code depends_on}). */
  @SuppressWarnings("unchecked")
  static void compactLists(Map<String, Object> map) {
    for (Map.Entry<String, Object> e : map.entrySet()) {
      if (e.getValue() instanceof List<?> list) {
        List<Object> compact = new ArrayList<>();
        for (Object o : list) {
          if (o == null) continue;
          if (o instanceof Map<?, ?> m) compactLists((Map<String, Object>) m);
          compact.add(o);
        }
        e.setValue(compact);
      } else if (e.getValue() instanceof Map<?, ?> m) {
        compactLists((Map<String, Object>) m);
      }
    }
  }

  private static boolean isBlank(Object v) {
    return v == null || v.toString().isBlank();
  }

  @SuppressWarnings("unchecked")
  static void put(Map<String, Object> root, String path, Object value) {
    String[] parts = path.split("\\.");
    Object current = root;
    for (int i = 0; i < parts.length; i++) {
      boolean last = i == parts.length - 1;
      Matcher m = INDEXED.matcher(parts[i]);
      if (m.matches()) {
        String key = m.group(1);
        int idx = Integer.parseInt(m.group(2));
        Map<String, Object> map = (Map<String, Object>) current;
        List<Object> list = (List<Object>) map.computeIfAbsent(key, k -> new ArrayList<>());
        while (list.size() <= idx) list.add(null);
        if (last) {
          list.set(idx, value);
        } else {
          if (list.get(idx) == null) list.set(idx, new LinkedHashMap<String, Object>());
          current = list.get(idx);
        }
      } else {
        Map<String, Object> map = (Map<String, Object>) current;
        if (last) {
          map.put(parts[i], value);
        } else {
          current = map.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
        }
      }
    }
  }
}
