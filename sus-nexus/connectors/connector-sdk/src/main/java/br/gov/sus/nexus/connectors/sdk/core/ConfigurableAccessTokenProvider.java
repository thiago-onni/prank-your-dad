package br.gov.sus.nexus.connectors.sdk.core;

import br.gov.sus.nexus.connectors.sdk.config.ConnectorConfig;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.Tokens;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token para o core: modo {@code oidc} usa client credentials via {@code quarkus-oidc-client}
 * (config {@code quarkus.oidc-client.*}); modo {@code static} (dev/test) usa token fixo.
 */
@ApplicationScoped
public class ConfigurableAccessTokenProvider implements AccessTokenProvider {

  private final ConnectorConfig config;
  private final Instance<OidcClient> oidcClient;
  private final AtomicReference<Tokens> cached = new AtomicReference<>();

  @Inject
  public ConfigurableAccessTokenProvider(ConnectorConfig config, Instance<OidcClient> oidcClient) {
    this.config = config;
    this.oidcClient = oidcClient;
  }

  @Override
  public String accessToken() {
    if (!"oidc".equalsIgnoreCase(config.core().auth().mode())) {
      return config.core().auth().staticToken();
    }
    Tokens tokens = cached.get();
    if (tokens == null
        || tokens.isAccessTokenExpired()
        || tokens.isAccessTokenWithinRefreshInterval()) {
      tokens = oidcClient.get().getTokens().await().atMost(Duration.ofSeconds(30));
      cached.set(tokens);
    }
    return tokens.getAccessToken();
  }
}
