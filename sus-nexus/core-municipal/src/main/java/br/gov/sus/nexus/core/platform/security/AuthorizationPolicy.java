package br.gov.sus.nexus.core.platform.security;

import java.util.Map;
import java.util.Set;

/**
 * Ponto de extensão de autorização (RBAC + ABAC). A implementação padrão é local, baseada em papéis
 * ({@link RoleBasedAuthorizationPolicy}); a integração com OPA ({@code policies/}) será uma
 * implementação alternativa desta interface, selecionada por configuração.
 */
public interface AuthorizationPolicy {

  /** Decisão de autorização. */
  record Decision(boolean allowed, String reason) {
    public static Decision allow() {
      return new Decision(true, "allow");
    }

    public static Decision deny(String reason) {
      return new Decision(false, reason);
    }
  }

  /** Entrada da política: quem, o quê, sobre qual recurso, com qual finalidade. */
  record Input(
      String tenantId,
      String actorId,
      Set<String> roles,
      String action,
      String resourceType,
      String resourceId,
      Purpose purpose,
      Map<String, Object> attributes) {}

  Decision evaluate(Input input);
}
