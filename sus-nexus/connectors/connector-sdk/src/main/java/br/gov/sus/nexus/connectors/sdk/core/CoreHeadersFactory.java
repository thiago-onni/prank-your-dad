package br.gov.sus.nexus.connectors.sdk.core;

import br.gov.sus.nexus.connectors.sdk.config.ConnectorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

/** Injeta {@code Authorization}, {@code X-Tenant-Id} e {@code X-Purpose-Of-Use} em toda chamada. */
@ApplicationScoped
public class CoreHeadersFactory implements ClientHeadersFactory {

  private final ConnectorConfig config;
  private final AccessTokenProvider tokens;

  @Inject
  public CoreHeadersFactory(ConnectorConfig config, AccessTokenProvider tokens) {
    this.config = config;
    this.tokens = tokens;
  }

  @Override
  public MultivaluedMap<String, String> update(
      MultivaluedMap<String, String> incomingHeaders,
      MultivaluedMap<String, String> clientOutgoingHeaders) {
    MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    headers.putSingle("Authorization", "Bearer " + tokens.accessToken());
    headers.putSingle(CoreApi.TENANT_ID, config.tenantId());
    headers.putSingle(CoreApi.PURPOSE, "integration_operations");
    return headers;
  }
}
