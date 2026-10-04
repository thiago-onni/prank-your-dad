package br.gov.sus.nexus.fhir.security;

import br.gov.sus.nexus.fhir.FhirConstants;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Identidade fake para perfis {@code %dev}/{@code %test}: {@code X-Test-User}, {@code
 * X-Test-Scopes} (separados por espaço), {@code X-Tenant-Id} e {@code X-Test-Patient}. Nunca ativo
 * em produção ({@code sus.fhir.security.mode=oidc}).
 */
@ApplicationScoped
@Identifier("test-headers")
public class TestHeaderIdentityResolver implements IdentityResolver {

  @Override
  public Identity resolve(ContainerRequestContext request) {
    String user = request.getHeaderString(FhirConstants.HEADER_TEST_USER);
    if (user == null || user.isBlank()) {
      return Identity.anonymous();
    }
    String rawScopes = request.getHeaderString(FhirConstants.HEADER_TEST_SCOPES);
    Set<SmartScope> scopes = new LinkedHashSet<>();
    if (rawScopes != null) {
      Arrays.stream(rawScopes.split("[\\s,]+"))
          .map(SmartScope::parse)
          .flatMap(Optional::stream)
          .forEach(scopes::add);
    }
    String tenant = request.getHeaderString(FhirConstants.HEADER_TENANT);
    String patient = request.getHeaderString(FhirConstants.HEADER_TEST_PATIENT);
    return new Identity(
        user.trim(),
        tenant == null ? null : tenant.trim(),
        Set.copyOf(scopes),
        Optional.ofNullable(patient).filter(p -> !p.isBlank()));
  }
}
