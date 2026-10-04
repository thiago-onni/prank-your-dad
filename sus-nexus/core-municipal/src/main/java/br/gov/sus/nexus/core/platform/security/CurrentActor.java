package br.gov.sus.nexus.core.platform.security;

import jakarta.enterprise.context.RequestScoped;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ator da requisição corrente (usuário/serviço autenticado), seus papéis, vínculos (CNES, equipes,
 * microáreas), tipo de cliente e a finalidade declarada ({@code X-Purpose-Of-Use}). Preenchido pelo
 * {@link br.gov.sus.nexus.core.platform.tenant.TenantFilter}. Em consumidores/jobs o ator é {@code
 * system}.
 */
@RequestScoped
public class CurrentActor {

  public static final String SYSTEM = "system";

  /** Tipo de cliente conforme contrato OPA ({@code subject.client_type}). */
  public enum ClientType {
    USER("user"),
    SERVICE("service"),
    AGENT("agent");

    private final String wire;

    ClientType(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }
  }

  private String actorId = SYSTEM;
  private Set<String> roles = Set.of();
  private Purpose purpose;
  private boolean breakGlass;
  private String breakGlassJustification;
  private List<String> cnes = List.of();
  private List<String> teams = List.of();
  private List<String> microareas = List.of();
  private ClientType clientType = ClientType.SERVICE;

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

  public String breakGlassJustification() {
    return breakGlassJustification;
  }

  public List<String> cnes() {
    return cnes;
  }

  public List<String> teams() {
    return teams;
  }

  public List<String> microareas() {
    return microareas;
  }

  public ClientType clientType() {
    return clientType;
  }

  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  public void set(String actorId, Set<String> roles, Purpose purpose, boolean breakGlass) {
    this.actorId = actorId == null ? SYSTEM : actorId;
    this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    this.purpose = purpose;
    this.breakGlass = breakGlass;
    this.clientType = inferClientType(this.actorId, this.roles);
  }

  public void setBindings(
      List<String> cnes, List<String> teams, List<String> microareas, ClientType clientType) {
    this.cnes = cnes == null ? List.of() : List.copyOf(cnes);
    this.teams = teams == null ? List.of() : List.copyOf(teams);
    this.microareas = microareas == null ? List.of() : List.copyOf(microareas);
    if (clientType != null) {
      this.clientType = clientType;
    }
  }

  public void setBreakGlassJustification(String justification) {
    this.breakGlassJustification = justification;
  }

  static ClientType inferClientType(String actorId, Set<String> roles) {
    if (roles.contains(Roles.AGENTE_IA)) {
      return ClientType.AGENT;
    }
    if (SYSTEM.equals(actorId)
        || actorId.startsWith("service-account-")
        || actorId.startsWith("connector-")) {
      return ClientType.SERVICE;
    }
    return ClientType.USER;
  }
}
