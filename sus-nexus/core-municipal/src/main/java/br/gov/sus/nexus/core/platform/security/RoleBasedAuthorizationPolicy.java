package br.gov.sus.nexus.core.platform.security;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

/**
 * Política local baseada em papéis. Matriz mínima da Fase 1; será substituída/complementada pela
 * avaliação OPA. {@code admin_municipal} tem acesso a tudo.
 */
@ApplicationScoped
@DefaultBean
public class RoleBasedAuthorizationPolicy implements AuthorizationPolicy {

  private static final Map<String, Set<String>> MATRIX =
      Map.ofEntries(
          Map.entry(
              "citizen:read",
              Set.of(
                  Roles.PROFISSIONAL_APS,
                  Roles.ACS,
                  Roles.REGULADOR,
                  Roles.AGENDADOR,
                  Roles.GESTOR,
                  Roles.OPERADOR_INTEGRACAO,
                  Roles.AGENTE_IA)),
          Map.entry(
              "citizen:register",
              Set.of(Roles.OPERADOR_INTEGRACAO, Roles.PROFISSIONAL_APS, Roles.ACS)),
          Map.entry("citizen:reveal_identifier", Set.of(Roles.PROFISSIONAL_APS, Roles.REGULADOR)),
          Map.entry("mpi:review", Set.of(Roles.GESTOR, Roles.PROFISSIONAL_APS)),
          Map.entry("mpi:merge", Set.of(Roles.GESTOR)),
          Map.entry("audit:read", Set.of(Roles.DPO, Roles.AUDITOR)),
          Map.entry("reference:read", Set.of()),
          Map.entry("terminology:read", Set.of()));

  @Override
  public Decision evaluate(Input input) {
    if (input.roles().contains(Roles.ADMIN_MUNICIPAL)) {
      return Decision.allow();
    }
    Set<String> allowed = MATRIX.get(input.action());
    if (allowed == null) {
      return Decision.deny("ação desconhecida: " + input.action());
    }
    if (allowed.isEmpty()) {
      return Decision.allow();
    }
    for (String role : input.roles()) {
      if (allowed.contains(role)) {
        return Decision.allow();
      }
    }
    return Decision.deny("papel insuficiente para " + input.action());
  }
}
