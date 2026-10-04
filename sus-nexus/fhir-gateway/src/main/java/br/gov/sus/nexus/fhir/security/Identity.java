package br.gov.sus.nexus.fhir.security;

import java.util.Optional;
import java.util.Set;

/**
 * Identidade autenticada da requisição.
 *
 * @param subject identificador do sujeito (sub do token ou usuário de teste) — nunca PII
 * @param tenantId tenant ({@code ibge_<código>}) resolvido do claim/header
 * @param scopes escopos SMART-like válidos
 * @param patientId id do Patient vinculado (contexto {@code patient/}), se houver
 */
public record Identity(
    String subject, String tenantId, Set<SmartScope> scopes, Optional<String> patientId) {

  public static Identity anonymous() {
    return new Identity(null, null, Set.of(), Optional.empty());
  }

  public boolean isAuthenticated() {
    return subject != null && !subject.isBlank();
  }

  public boolean hasTenant() {
    return tenantId != null && !tenantId.isBlank();
  }

  /** Verifica se algum escopo concede a permissão para o tipo. */
  public boolean grants(String type, Permission permission) {
    return scopes.stream().anyMatch(s -> s.grants(type, permission));
  }

  /**
   * Verdadeiro quando a leitura do tipo é concedida por escopo completo ({@code .read}/{@code *}).
   */
  public boolean hasFullRead(String type) {
    return scopes.stream().anyMatch(s -> !s.granular() && s.grants(type, Permission.READ));
  }

  /** Verdadeiro quando a permissão só é obtida por escopos de contexto {@code patient/}. */
  public boolean onlyPatientContext(String type, Permission permission) {
    boolean any = false;
    for (SmartScope s : scopes) {
      if (s.grants(type, permission)) {
        any = true;
        if (!s.isPatientContext()) {
          return false;
        }
      }
    }
    return any;
  }

  /** Permissão concedida por escopo que nomeia o tipo (não pelo curinga {@code *}). */
  public boolean grantsExplicitly(String type, Permission permission) {
    return scopes.stream()
        .anyMatch(s -> type.equals(s.resourceType()) && s.permissions().contains(permission));
  }

  public boolean hasSystemScope(String type, Permission permission) {
    return scopes.stream().anyMatch(s -> s.isSystemContext() && s.grants(type, permission));
  }
}
