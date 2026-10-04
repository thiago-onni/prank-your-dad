package br.gov.sus.nexus.core.platform.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ponto de extensão de autorização (RBAC + ABAC). A implementação padrão é local, baseada em papéis
 * ({@link RoleBasedAuthorizationPolicy}); {@link OpaAuthorizationPolicy} ({@code
 * sus.authz.mode=opa}) delega ao OPA ({@code policies/}) e devolve obrigações.
 */
public interface AuthorizationPolicy {

  /**
   * Obrigações que o serviço DEVE cumprir (contrato de {@code policies/README.md}). Quando a
   * política não as informa (RBAC local), aplicam-se os padrões: sem máscara extra, sem redação,
   * sempre registrar acesso.
   */
  record Obligations(
      boolean maskIdentifiers,
      List<String> redactFields,
      boolean logAccess,
      boolean requireJustification,
      boolean alertDpo) {

    public static final Obligations NONE = new Obligations(false, List.of(), true, false, false);

    public Obligations {
      redactFields = redactFields == null ? List.of() : List.copyOf(redactFields);
    }
  }

  /** Decisão de autorização. */
  record Decision(
      boolean allowed,
      String reason,
      List<String> reasons,
      Obligations obligations,
      String policyVersion) {

    public Decision {
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
      obligations = obligations == null ? Obligations.NONE : obligations;
    }

    public Decision(boolean allowed, String reason) {
      this(allowed, reason, List.of(reason), Obligations.NONE, null);
    }

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
      Map<String, Object> attributes) {

    public Input {
      roles = roles == null ? Set.of() : Set.copyOf(roles);
      attributes =
          attributes == null
              ? Map.of()
              : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
  }

  Decision evaluate(Input input);
}
