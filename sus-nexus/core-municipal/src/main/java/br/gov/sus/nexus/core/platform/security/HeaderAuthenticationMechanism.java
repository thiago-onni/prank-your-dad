package br.gov.sus.nexus.core.platform.security;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Identidade fake para dev/test (OIDC desligado): headers {@code X-Test-User} e {@code
 * X-Test-Roles} (lista separada por vírgula). Só é instanciado nos perfis {@code dev}/{@code test}
 * e ainda assim precisa de {@code sus.security.header-auth.enabled=true}. Nunca ativo em prod.
 */
@ApplicationScoped
@IfBuildProfile(anyOf = {"dev", "test"})
public class HeaderAuthenticationMechanism implements HttpAuthenticationMechanism {

  public static final String USER_HEADER = "X-Test-User";
  public static final String ROLES_HEADER = "X-Test-Roles";

  @ConfigProperty(name = "sus.security.header-auth.enabled", defaultValue = "false")
  boolean enabled;

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    if (!enabled) {
      return Uni.createFrom().nullItem();
    }
    String user = context.request().getHeader(USER_HEADER);
    if (user == null || user.isBlank()) {
      return Uni.createFrom().nullItem();
    }
    String rolesHeader = context.request().getHeader(ROLES_HEADER);
    Set<String> roles =
        rolesHeader == null
            ? Set.of()
            : Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    QuarkusSecurityIdentity.Builder builder =
        QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(user.trim()));
    roles.forEach(builder::addRole);
    return Uni.createFrom().item(builder.build());
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", "X-Test-User"));
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of();
  }
}
