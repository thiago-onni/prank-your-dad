package br.gov.sus.nexus.fhir.persistence;

import br.gov.sus.nexus.fhir.capability.SearchParamDef;
import java.util.List;

/**
 * Consulta de busca já validada contra o registro de capacidades.
 *
 * @param resourceType tipo pesquisado
 * @param filters filtros (AND entre filtros, OR entre valores de um mesmo filtro)
 * @param afterId id do último recurso da página anterior (keyset), ou {@code null}
 * @param count tamanho da página
 */
public record SearchQuery(
    String resourceType, List<SearchFilter> filters, String afterId, int count) {

  /** Um parâmetro de busca com seu modificador e valores (separados por vírgula = OR). */
  public record SearchFilter(SearchParamDef def, String modifier, List<String> values) {}
}
