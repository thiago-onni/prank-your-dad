package br.gov.sus.nexus.fhir.security;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Identidade a partir do token OIDC (Keycloak): escopos do claim {@code scope} (separados por
 * espaço), tenant do claim {@code municipality_id} e paciente do claim {@code patient}. O header
 * {@code X-Tenant-Id} só é aceito quando o token não traz o claim de tenant (chamadas internas
 * entre serviços com credenciais de sistema).
 */
@ApplicationScoped
@Identifier("oidc")
public class OidcIdentityResolver implements IdentityResolver {

  @Inject Instance<SecurityIdentity> securityIdentity;
  @Inject Instance<JsonWebToken> jwt;
  @Inject FhirGatewayConfig config;

  @Override
  public Identity resolve(ContainerRequestContext request) {
    if (securityIdentity.isUnsatisfied() || jwt.isUnsatisfied()) {
      return Identity.anonymous();
    }
    SecurityIdentity identity = securityIdentity.get();
    if (identity == null || identity.isAnonymous()) {
      return Identity.anonymous();
    }
    JsonWebToken token = jwt.get();
    String subject = token.getSubject();
    if (subject == null || subject.isBlank()) {
      return Identity.anonymous();
    }
    Set<SmartScope> scopes = new LinkedHashSet<>();
    Object scopeClaim = token.getClaim(config.security().scopeClaim());
    for (String raw : scopeStrings(scopeClaim)) {
      SmartScope.parse(raw).ifPresent(scopes::add);
    }
    Object tenantClaim = token.getClaim(config.security().tenantClaim());
    String tenant = tenantClaim == null ? null : tenantClaim.toString();
    if ((tenant == null || tenant.isBlank())
        && scopes.stream().anyMatch(SmartScope::isSystemContext)) {
      tenant = request.getHeaderString(FhirConstants.HEADER_TENANT);
    }
    Object patientClaim = token.getClaim("patient");
    return new Identity(
        subject,
        tenant,
        Set.copyOf(scopes),
        Optional.ofNullable(patientClaim).map(Object::toString).filter(p -> !p.isBlank()));
  }

  private static Collection<String> scopeStrings(Object claim) {
    if (claim == null) {
      return Set.of();
    }
    if (claim instanceof Collection<?> c) {
      Set<String> out = new LinkedHashSet<>();
      for (Object o : c) {
        out.add(String.valueOf(o));
      }
      return out;
    }
    return new LinkedHashSet<>(Arrays.asList(claim.toString().split("\\s+")));
  }
}
