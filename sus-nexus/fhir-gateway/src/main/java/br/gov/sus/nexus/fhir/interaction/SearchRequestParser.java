package br.gov.sus.nexus.fhir.interaction;

import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.capability.ResourceCapability;
import br.gov.sus.nexus.fhir.capability.SearchParamDef;
import br.gov.sus.nexus.fhir.capability.SearchParamType;
import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import br.gov.sus.nexus.fhir.persistence.SearchQuery;
import br.gov.sus.nexus.fhir.persistence.SearchQuery.SearchFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Converte os parâmetros HTTP de uma busca em {@link SearchQuery}, validando contra o registro de
 * capacidades (parâmetros desconhecidos → 400). Suporta {@code _count}, {@code _cursor}, {@code
 * _sort} (parâmetros de data e {@code _lastUpdated}, prefixo {@code -} para decrescente), {@code
 * _total=accurate}, {@code _include=Tipo:param} (conforme registro) e modificadores {@code :exact},
 * {@code :contains} e {@code :Tipo} (referência).
 */
@ApplicationScoped
public class SearchRequestParser {

  private static final Set<String> IGNORED = Set.of("_format", "_pretty");
  public static final String CURSOR_PARAM = "_cursor";
  public static final String INCLUDE_PARAM = "_include";
  public static final String SORT_PARAM = "_sort";
  public static final String TOTAL_PARAM = "_total";

  @Inject CapabilityRegistry registry;
  @Inject FhirGatewayConfig config;
  @Inject SearchCursor cursor;

  /** {@code _include} resolvido: parâmetro de referência do tipo pesquisado. */
  public record Include(String sourceType, SearchParamDef param, String targetType) {}

  /** Query pronta + parâmetros normalizados (para gerar o próximo cursor) + controles. */
  public record Parsed(
      SearchQuery query,
      Map<String, List<String>> params,
      List<Include> includes,
      boolean totalAccurate) {}

  public Parsed parse(String type, Map<String, List<String>> rawParams) {
    List<String> cursorValues = rawParams.get(CURSOR_PARAM);
    if (cursorValues != null && !cursorValues.isEmpty()) {
      SearchCursor.Payload payload = cursor.decode(cursorValues.get(0));
      if (!type.equals(payload.type())) {
        throw FhirException.invalid("Cursor não pertence a este tipo de recurso", CURSOR_PARAM);
      }
      return build(
          type, payload.params(), payload.afterId(), payload.afterSortKey(), payload.count());
    }

    Map<String, List<String>> normalized = new TreeMap<>();
    int count = config.search().defaultCount();
    for (Map.Entry<String, List<String>> e : rawParams.entrySet()) {
      String key = e.getKey();
      if (IGNORED.contains(key)) {
        continue;
      }
      if ("_count".equals(key)) {
        count = parseCount(e.getValue());
        continue;
      }
      List<String> values = new ArrayList<>();
      for (String v : e.getValue()) {
        if (v != null && !v.isBlank()) {
          values.add(v);
        }
      }
      if (values.isEmpty()) {
        continue;
      }
      normalized.put(key, values);
    }
    return build(type, normalized, null, null, count);
  }

  private int parseCount(List<String> values) {
    try {
      int n = Integer.parseInt(values.get(0));
      if (n < 1) {
        throw FhirException.invalid("_count deve ser maior que zero", "_count");
      }
      return Math.min(n, config.search().maxCount());
    } catch (NumberFormatException e) {
      throw FhirException.invalid("_count inválido", "_count");
    }
  }

  private Parsed build(
      String type,
      Map<String, List<String>> params,
      String afterId,
      String afterSortKey,
      int count) {
    List<SearchFilter> filters = new ArrayList<>();
    List<Include> includes = new ArrayList<>();
    SearchQuery.Sort sort = null;
    boolean total = false;
    for (Map.Entry<String, List<String>> e : params.entrySet()) {
      String key = e.getKey();
      switch (key) {
        case INCLUDE_PARAM -> {
          for (String raw : e.getValue()) {
            for (String v : raw.split(",")) {
              includes.add(parseInclude(type, v.trim()));
            }
          }
          continue;
        }
        case SORT_PARAM -> {
          sort = parseSort(type, e.getValue());
          continue;
        }
        case TOTAL_PARAM -> {
          total = parseTotal(e.getValue());
          continue;
        }
        default -> {
          // parâmetro de busca comum: tratado abaixo
        }
      }
      String name = key;
      String modifier = "";
      int colon = key.indexOf(':');
      if (colon > 0) {
        name = key.substring(0, colon);
        modifier = key.substring(colon + 1);
      }
      final String paramName = name;
      SearchParamDef def =
          registry
              .searchParam(type, paramName)
              .orElseThrow(
                  () ->
                      FhirException.invalid(
                          "Parâmetro de busca não suportado para " + type + ": " + paramName,
                          paramName));
      // parâmetro repetido = AND entre ocorrências; vírgula = OR entre valores
      for (String raw : e.getValue()) {
        filters.add(new SearchFilter(def, modifier, Arrays.asList(raw.split(","))));
      }
    }
    SearchQuery query = new SearchQuery(type, filters, sort, afterId, afterSortKey, count);
    return new Parsed(query, params, includes, total);
  }

  private Include parseInclude(String type, String value) {
    String[] parts = value.split(":");
    if (parts.length < 2 || parts.length > 3 || !type.equals(parts[0])) {
      throw FhirException.invalid(
          "_include inválido para " + type + ": " + value + " (esperado " + type + ":param)",
          INCLUDE_PARAM);
    }
    ResourceCapability cap = registry.resource(type).orElseThrow();
    if (!cap.supportsInclude(parts[1])) {
      throw FhirException.invalid(
          "_include não suportado: " + value + " (aceitos: " + supportedIncludes(cap) + ")",
          INCLUDE_PARAM);
    }
    SearchParamDef def = cap.searchParam(parts[1]).orElseThrow();
    String target = parts.length == 3 ? parts[2] : null;
    if (target != null && !def.targets().contains(target)) {
      throw FhirException.invalid("_include com tipo-alvo não suportado: " + value, INCLUDE_PARAM);
    }
    return new Include(type, def, target);
  }

  private static String supportedIncludes(ResourceCapability cap) {
    return cap.includes().stream()
        .map(i -> cap.type() + ":" + i)
        .reduce((a, b) -> a + ", " + b)
        .orElse("nenhum");
  }

  private SearchQuery.Sort parseSort(String type, List<String> values) {
    if (values.size() != 1 || values.get(0).contains(",")) {
      throw FhirException.invalid("_sort aceita um único parâmetro", SORT_PARAM);
    }
    String raw = values.get(0).trim();
    boolean desc = raw.startsWith("-");
    String name = desc ? raw.substring(1) : raw;
    SearchParamDef def =
        registry
            .searchParam(type, name)
            .orElseThrow(
                () ->
                    FhirException.invalid(
                        "_sort por parâmetro desconhecido para " + type + ": " + name, SORT_PARAM));
    if (def.type() != SearchParamType.DATE) {
      throw FhirException.invalid(
          "_sort suportado apenas para parâmetros de data e _lastUpdated: " + name, SORT_PARAM);
    }
    return new SearchQuery.Sort(def, desc);
  }

  private static boolean parseTotal(List<String> values) {
    String v = values.get(0).trim();
    return switch (v) {
      case "accurate" -> true;
      case "none", "estimate" -> false;
      default -> throw FhirException.invalid("_total inválido: " + v, TOTAL_PARAM);
    };
  }
}
