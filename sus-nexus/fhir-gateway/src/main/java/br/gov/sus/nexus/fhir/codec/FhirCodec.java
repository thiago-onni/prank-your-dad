package br.gov.sus.nexus.fhir.codec;

import org.hl7.fhir.r4.model.Resource;

/**
 * Interface própria de (de)serialização FHIR R4 JSON. A implementação usa o {@code JsonParser} de
 * {@code org.hl7.fhir.r4} em modo estrito (ADR-003), isolado atrás desta interface.
 */
public interface FhirCodec {

  /**
   * Faz o parse estrito de um recurso FHIR JSON.
   *
   * @throws FhirParseException quando o JSON é inválido, contém propriedades desconhecidas ou tipos
   *     incompatíveis
   */
  Resource parse(String json);

  /** Serializa um recurso para JSON compacto. */
  String encode(Resource resource);

  /** Serializa um recurso para JSON legível (usado em testes/diagnóstico). */
  String encodePretty(Resource resource);

  /** Cria uma cópia profunda de um recurso. */
  @SuppressWarnings("unchecked")
  default <T extends Resource> T copy(T resource) {
    return (T) resource.copy();
  }
}
