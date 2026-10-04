package br.gov.sus.nexus.core.terminology.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.util.Optional;

/** API pública do módulo terminology (SIGTAP, CID10, CIAP2, CBO). Terminologia é global. */
public interface TerminologyService {

  /** Verdadeiro se o código existe no sistema e está vigente na competência (AAAAMM). */
  boolean isValid(String system, String code, String competence);

  Optional<CodeDto> find(String system, String code, String competence);

  /** Busca por código exato e/ou texto do display (trigram + unaccent) filtrando competência. */
  Page<CodeDto> search(
      String system, String q, String code, String competence, String cursor, Integer limit);
}
