package br.gov.sus.nexus.fhir.interaction;

import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.capability.SearchParamDef;
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
 * capacidades (parâmetros desconhecidos → 400). Suporta {@code _count}, {@code _cursor} e
 * modificadores {@code :exact}, {@code :contains} e {@code :Tipo} (referência).
 */
@ApplicationScoped
public class SearchRequestParser {

  private static final Set<String> IGNORED = Set.of("_format", "_pretty");
  public static final String CURSOR_PARAM = "_cursor";

  @Inject CapabilityRegistry registry;
  @Inject FhirGatewayConfig config;
  @Inject SearchCursor cursor;

  /** Query pronta + parâmetros normalizados (para gerar o próximo cursor). */
  public record Parsed(SearchQuery query, Map<String, List<String>> params) {}

  public Parsed parse(String type, Map<String, List<String>> rawParams) {
    List<String> cursorValues = rawParams.get(CURSOR_PARAM);
    if (cursorValues != null && !cursorValues.isEmpty()) {
      SearchCursor.Payload payload = cursor.decode(cursorValues.get(0));
      if (!type.equals(payload.type())) {
        throw FhirException.invalid("Cursor não pertence a este tipo de recurso", CURSOR_PARAM);
      }
      return new Parsed(
          toQuery(type, payload.params(), payload.afterId(), payload.count()), payload.params());
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
    return new Parsed(toQuery(type, normalized, null, count), normalized);
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

  private SearchQuery toQuery(
      String type, Map<String, List<String>> params, String afterId, int count) {
    List<SearchFilter> filters = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : params.entrySet()) {
      String key = e.getKey();
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
    return new SearchQuery(type, filters, afterId, count);
  }
}
