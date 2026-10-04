package br.gov.sus.nexus.fhir.capability;

/** Tipos de parâmetro de busca implementados (subconjunto de search-param-type). */
public enum SearchParamType {
  TOKEN("token"),
  STRING("string"),
  DATE("date"),
  REFERENCE("reference"),
  QUANTITY("quantity");

  private final String code;

  SearchParamType(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
