package br.gov.sus.nexus.fhir.persistence;

import br.gov.sus.nexus.fhir.capability.SearchParamDef;
import java.util.List;

/**
 * Consulta de busca já validada contra o registro de capacidades.
 *
 * @param resourceType tipo pesquisado
 * @param filters filtros (AND entre filtros, OR entre valores de um mesmo filtro)
 * @param sort ordenação ({@code null} = por id)
 * @param afterId id do último recurso da página anterior (keyset), ou {@code null}
 * @param afterSortKey chave de ordenação do último recurso da página anterior (ISO-8601) ou {@code
 *     null}
 * @param count tamanho da página
 */
public record SearchQuery(
    String resourceType,
    List<SearchFilter> filters,
    Sort sort,
    String afterId,
    String afterSortKey,
    int count) {

  public SearchQuery(String resourceType, List<SearchFilter> filters, String afterId, int count) {
    this(resourceType, filters, null, afterId, null, count);
  }

  /** Um parâmetro de busca com seu modificador e valores (separados por vírgula = OR). */
  public record SearchFilter(SearchParamDef def, String modifier, List<String> values) {}

  /** Ordenação por parâmetro de data ({@code _sort=date} / {@code _sort=-_lastUpdated}). */
  public record Sort(SearchParamDef param, boolean descending) {}

  public SearchQuery withFilters(List<SearchFilter> newFilters) {
    return new SearchQuery(resourceType, newFilters, sort, afterId, afterSortKey, count);
  }
}
