package br.gov.sus.nexus.fhir.projection;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Sobe um WireMock que simula o core municipal (endpoints canônicos + token client-credentials) e
 * aponta o REST client e o provedor de token para ele.
 */
public class CoreWireMockResource implements QuarkusTestResourceLifecycleManager {

  private static WireMockServer server;

  public static WireMockServer server() {
    return server;
  }

  @Override
  public Map<String, String> start() {
    server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    server.start();
    return Map.of(
        "quarkus.rest-client.core-municipal.url",
        server.baseUrl(),
        "sus.fhir.core.token-url",
        server.baseUrl() + "/auth/token");
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop();
      server = null;
    }
  }
}
