package br.gov.sus.nexus.connectors.sdk.runtime;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/** Sobe um WireMock em porta dinâmica simulando o core e injeta {@code wiremock.port}. */
public class WireMockCoreResource implements QuarkusTestResourceLifecycleManager {

  public static WireMockServer server;

  @Override
  public Map<String, String> start() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    return Map.of("wiremock.port", String.valueOf(server.port()));
  }

  @Override
  public void stop() {
    if (server != null) server.stop();
  }
}
