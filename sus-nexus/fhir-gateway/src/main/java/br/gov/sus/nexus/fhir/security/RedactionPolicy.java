package br.gov.sus.nexus.fhir.security;

import org.hl7.fhir.r4.model.Resource;

/**
 * Mascaramento/redação aplicado após a leitura e antes da serialização. Recebe uma cópia do recurso
 * e devolve a versão redigida (pode ser a mesma instância).
 */
public interface RedactionPolicy {

  Resource apply(Identity identity, Resource resource);
}
