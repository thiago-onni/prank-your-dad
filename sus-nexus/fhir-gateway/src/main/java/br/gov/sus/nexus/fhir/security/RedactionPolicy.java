package br.gov.sus.nexus.fhir.security;

import org.hl7.fhir.r4.model.Resource;

/**
 * Mascaramento/redação aplicado após a leitura e antes da serialização. Recebe uma cópia do recurso
 * e devolve a versão redigida (pode ser a mesma instância). {@link #withhold} permite omitir o
 * recurso por inteiro (403 em leitura; omitido em busca/histórico).
 */
public interface RedactionPolicy {

  Resource apply(Identity identity, Resource resource);

  /** Verdadeiro quando a identidade não pode ver o recurso de forma alguma. */
  default boolean withhold(Identity identity, Resource resource) {
    return false;
  }
}
