package br.gov.sus.nexus.connectors.rnds;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Um WireMock com duas portas dinâmicas: HTTP (core, fhir-gateway, EHR da RNDS, token do Keycloak)
 * e HTTPS com <b>certificado de cliente obrigatório</b> (serviço de autenticação da RNDS). Os
 * keystores de teste são autoassinados (src/test/resources/certs, senha {@code changeit}).
 */
public class WireMockRndsResource implements QuarkusTestResourceLifecycleManager {

  public static WireMockServer server;

  public static Path certs() {
    try {
      return Path.of(WireMockRndsResource.class.getResource("/certs/client.p12").toURI())
          .getParent();
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public Map<String, String> start() {
    Path certs = certs();
    server =
        new WireMockServer(
            WireMockConfiguration.options()
                .dynamicPort()
                .dynamicHttpsPort()
                .keystorePath(certs.resolve("server.p12").toString())
                .keystoreType("PKCS12")
                .keystorePassword("changeit")
                .keyManagerPassword("changeit")
                .needClientAuth(true)
                .trustStorePath(certs.resolve("server-truststore.p12").toString())
                .trustStoreType("PKCS12")
                .trustStorePassword("changeit"));
    server.start();
    return Map.of(
        "wiremock.port", String.valueOf(server.port()),
        "wiremock.https-port", String.valueOf(server.httpsPort()),
        "wiremock.certs", certs.toString());
  }

  @Override
  public void stop() {
    if (server != null) server.stop();
  }
}
