package br.gov.sus.nexus.fhir.mapping;

import br.gov.sus.nexus.fhir.FhirTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Leitura de fixtures canônicas sem CDI (testes unitários dos mapeadores). */
final class MapperTestSupport {

  private MapperTestSupport() {}

  static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  static <T> T canonical(String fixture, Class<T> type) {
    try {
      return JSON.readValue(FhirTestSupport.fixture(fixture), type);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
