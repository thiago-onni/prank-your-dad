package br.gov.sus.nexus.fhir.projection;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

/**
 * Acrescenta {@code Authorization: Bearer} (client-credentials) e finalidade às chamadas ao core.
 */
@ApplicationScoped
public class CoreClientHeadersFactory implements ClientHeadersFactory {

  @Inject CoreTokenProvider tokens;

  @Override
  public MultivaluedMap<String, String> update(
      MultivaluedMap<String, String> incomingHeaders,
      MultivaluedMap<String, String> clientOutgoingHeaders) {
    MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    Optional<String> token = tokens.accessToken();
    token.ifPresent(t -> headers.putSingle("Authorization", "Bearer " + t));
    headers.putSingle("X-Purpose-Of-Use", "integration_operations");
    return headers;
  }
}
