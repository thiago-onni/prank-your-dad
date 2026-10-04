package br.gov.sus.nexus.fhir.security;

import br.gov.sus.nexus.fhir.capability.Interaction;

/**
 * Ponto de extensão de autorização (futuro: OPA via {@code policies/}). A implementação padrão
 * decide por escopo SMART-like + tenant ({@link ScopeAccessPolicy}).
 */
public interface AccessPolicy {

  /** Pedido de acesso avaliado pela política. */
  record AccessRequest(
      Identity identity,
      Interaction interaction,
      String resourceType,
      String resourceId,
      String purposeOfUse) {}

  /** Decisão da política; {@code reason} é seguro para log (sem PII). */
  record Decision(boolean allowed, String reason) {
    public static Decision allow() {
      return new Decision(true, "allow");
    }

    public static Decision deny(String reason) {
      return new Decision(false, reason);
    }
  }

  Decision evaluate(AccessRequest request);
}
