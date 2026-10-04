package br.gov.sus.nexus.core.platform.tenant;

import br.gov.sus.nexus.core.platform.errors.ProblemDetails;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.security.Purpose;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.MDC;

/**
 * Exige tenant em toda requisição JAX-RS: claim JWT {@code municipality_id} (Keycloak) ou, quando
 * {@code sus.tenant.header-enabled=true} (dev/test), header {@code X-Tenant-Id}. Também popula
 * {@link CurrentActor} (ator, papéis, finalidade).
 */
@Provider
@Priority(Priorities.AUTHORIZATION - 10)
public class TenantFilter implements ContainerRequestFilter {

  public static final String HEADER = "X-Tenant-Id";
  public static final String CLAIM = "municipality_id";
  public static final String BREAK_GLASS_HEADER = "X-Break-Glass";
  public static final String BREAK_GLASS_JUSTIFICATION_HEADER = "X-Break-Glass-Justification";

  /** Vínculos fake (somente dev/test, com header-enabled): listas separadas por vírgula. */
  public static final String TEST_CNES_HEADER = "X-Test-Cnes";

  public static final String TEST_TEAMS_HEADER = "X-Test-Teams";
  public static final String TEST_MICROAREAS_HEADER = "X-Test-Microareas";

  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;
  @Inject SecurityIdentity identity;

  @ConfigProperty(name = "sus.tenant.header-enabled", defaultValue = "false")
  boolean headerEnabled;

  @Override
  public void filter(ContainerRequestContext request) {
    if (identity.isAnonymous()) {
      request.abortWith(
          problem(401, "Não autenticado", "Credenciais ausentes ou inválidas", "unauthorized"));
      return;
    }

    Optional<String> tenant = fromClaim();
    if (tenant.isEmpty() && headerEnabled) {
      tenant = Optional.ofNullable(request.getHeaderString(HEADER)).map(String::trim);
    }
    if (tenant.isEmpty() || !TenantContext.TENANT_PATTERN.matcher(tenant.get()).matches()) {
      request.abortWith(
          problem(
              400,
              "Tenant obrigatório",
              "tenant não resolvido (claim municipality_id ou header X-Tenant-Id)",
              "tenant-required"));
      return;
    }
    tenantContext.set(tenant.get());
    MDC.put("tenant_id", tenant.get());

    Purpose purpose = Purpose.parse(request.getHeaderString(Purpose.HEADER)).orElse(null);
    boolean breakGlass = "true".equalsIgnoreCase(request.getHeaderString(BREAK_GLASS_HEADER));
    currentActor.set(identity.getPrincipal().getName(), identity.getRoles(), purpose, breakGlass);
    currentActor.setBreakGlassJustification(
        request.getHeaderString(BREAK_GLASS_JUSTIFICATION_HEADER));
    currentActor.setBindings(
        binding(request, "cnes", TEST_CNES_HEADER),
        binding(request, "teams", TEST_TEAMS_HEADER),
        binding(request, "microareas", TEST_MICROAREAS_HEADER),
        null);
  }

  /**
   * Vínculos do token (claims {@code cnes}, {@code teams}, {@code microareas}) ou headers de teste.
   */
  private List<String> binding(ContainerRequestContext request, String claim, String header) {
    if (identity.getPrincipal() instanceof JsonWebToken jwt) {
      Object value = jwt.getClaim(claim);
      if (value instanceof Collection<?> c) {
        return c.stream().map(Object::toString).toList();
      }
      if (value instanceof jakarta.json.JsonArray arr) {
        return arr.getValuesAs(jakarta.json.JsonString.class).stream()
            .map(jakarta.json.JsonString::getString)
            .toList();
      }
      if (value != null) {
        return List.of(value.toString());
      }
    }
    if (headerEnabled) {
      String raw = request.getHeaderString(header);
      if (raw != null && !raw.isBlank()) {
        return Arrays.stream(raw.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
      }
    }
    return List.of();
  }

  private Optional<String> fromClaim() {
    if (identity.getPrincipal() instanceof JsonWebToken jwt) {
      Object claim = jwt.getClaim(CLAIM);
      if (claim != null) {
        return Optional.of(claim.toString());
      }
    }
    Object attr = identity.getAttribute(CLAIM);
    return Optional.ofNullable(attr).map(Object::toString);
  }

  private static Response problem(int status, String title, String detail, String code) {
    Object corr = MDC.get("correlation_id");
    ProblemDetails body =
        new ProblemDetails(
            "urn:sus-nexus:problem:" + code,
            title,
            status,
            detail,
            null,
            corr != null ? corr.toString() : null,
            null);
    return Response.status(status).type(ProblemDetails.MEDIA_TYPE).entity(body).build();
  }
}
