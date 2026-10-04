package br.gov.sus.nexus.fhir.mapping;

import br.gov.sus.nexus.fhir.FhirConstants;

/**
 * IDs internos do core são ULIDs prefixados ({@code cit_01H...}); o sublinhado não é permitido em
 * ids FHIR ({@code [A-Za-z0-9\-\.]}), então o id FHIR é a parte após o prefixo e o id canônico
 * completo é preservado como {@code identifier} próprio.
 */
public final class CanonicalIds {

  private CanonicalIds() {}

  public static String toFhirId(String canonicalId) {
    if (canonicalId == null || canonicalId.isBlank()) {
      throw new IllegalArgumentException("id canônico obrigatório");
    }
    String id = canonicalId;
    int underscore = id.indexOf('_');
    if (underscore > 0 && underscore < 6) {
      id = id.substring(underscore + 1);
    }
    if (!id.matches(FhirConstants.FHIR_ID_PATTERN)) {
      throw new IllegalArgumentException("id canônico não conversível para id FHIR");
    }
    return id;
  }
}
