package br.gov.sus.nexus.connectors.lis;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/** WireMock simulando o core em porta dinâmica. */
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
