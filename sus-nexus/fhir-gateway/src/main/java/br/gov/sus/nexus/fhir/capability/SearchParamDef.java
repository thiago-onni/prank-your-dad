package br.gov.sus.nexus.fhir.capability;

import java.util.List;

/**
 * Definição de um parâmetro de busca implementado.
 *
 * @param name nome do parâmetro (ex.: {@code identifier})
 * @param type tipo FHIR do parâmetro
 * @param expression expressão FHIRPath usada para extrair os valores indexados; {@code null} para
 *     parâmetros computados diretamente sobre a tabela de recursos ({@code _id}, {@code
 *     _lastUpdated})
 * @param documentation texto para o CapabilityStatement
 * @param targets tipos-alvo para parâmetros de referência
 */
public record SearchParamDef(
    String name,
    SearchParamType type,
    String expression,
    String documentation,
    List<String> targets) {

  public static SearchParamDef token(String name, String expression, String doc) {
    return new SearchParamDef(name, SearchParamType.TOKEN, expression, doc, List.of());
  }

  public static SearchParamDef string(String name, String expression, String doc) {
    return new SearchParamDef(name, SearchParamType.STRING, expression, doc, List.of());
  }

  public static SearchParamDef date(String name, String expression, String doc) {
    return new SearchParamDef(name, SearchParamType.DATE, expression, doc, List.of());
  }

  public static SearchParamDef quantity(String name, String expression, String doc) {
    return new SearchParamDef(name, SearchParamType.QUANTITY, expression, doc, List.of());
  }

  public static SearchParamDef reference(
      String name, String expression, String doc, String... targets) {
    return new SearchParamDef(name, SearchParamType.REFERENCE, expression, doc, List.of(targets));
  }

  /** Parâmetros computados ({@code _id}, {@code _lastUpdated}) não possuem expressão. */
  public boolean isComputed() {
    return expression == null;
  }
}
