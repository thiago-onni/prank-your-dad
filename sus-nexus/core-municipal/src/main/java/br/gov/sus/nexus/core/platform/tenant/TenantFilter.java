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
