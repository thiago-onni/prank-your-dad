package br.gov.sus.nexus.core.platform.security;

import jakarta.enterprise.context.RequestScoped;
import java.util.Optional;
import java.util.Set;

/**
 * Ator da requisição corrente (usuário/serviço autenticado), seus papéis e a finalidade declarada
 * ({@code X-Purpose-Of-Use}). Preenchido pelo {@link
 * br.gov.sus.nexus.core.platform.tenant.TenantFilter}.
 */
@RequestScoped
public class CurrentActor {

  public static final String SYSTEM = "system";

  private String actorId = SYSTEM;
  private Set<String> roles = Set.of();
  private Purpose purpose;
  private boolean breakGlass;

  public String actorId() {
    return actorId;
  }

  public Set<String> roles() {
    return roles;
  }

  public Optional<Purpose> purpose() {
    return Optional.ofNullable(purpose);
  }

  public boolean breakGlass() {
    return breakGlass;
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  public void set(String actorId, Set<String> roles, Purpose purpose, boolean breakGlass) {
    this.actorId = actorId == null ? SYSTEM : actorId;
    this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    this.purpose = purpose;
    this.breakGlass = breakGlass;
  }
}
