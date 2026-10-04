package br.gov.sus.nexus.fhir.capability;

/** Tipos de parâmetro de busca suportados (subconjunto de FHIR search-param-type). */
public enum SearchParamType {
  TOKEN("token"),
  STRING("string"),
  DATE("date"),
  REFERENCE("reference");

  private final String code;

  SearchParamType(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
