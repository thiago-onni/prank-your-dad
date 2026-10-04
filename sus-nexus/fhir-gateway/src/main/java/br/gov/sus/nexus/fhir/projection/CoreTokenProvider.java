package br.gov.sus.nexus.fhir.projection;

import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Token OAuth2 client-credentials para o core municipal, com cache até a expiração (margem de 30
 * s). Sem {@code sus.fhir.core.token-url} configurada, nenhum token é enviado (dev local).
 */
@ApplicationScoped
public class CoreTokenProvider {

  private static final Logger LOG = Logger.getLogger(CoreTokenProvider.class);

  @Inject FhirGatewayConfig config;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper json = new ObjectMapper();
  private volatile String token;
  private volatile Instant expiresAt = Instant.EPOCH;

  public synchronized Optional<String> accessToken() {
    Optional<String> url = config.core().tokenUrl().filter(u -> !u.isBlank());
    if (url.isEmpty()) {
      return Optional.empty();
    }
    if (token != null && Instant.now().isBefore(expiresAt)) {
      return Optional.of(token);
    }
    try {
      fetch(url.get());
      return Optional.ofNullable(token);
    } catch (IOException e) {
      throw new ProjectionException(
          "Falha ao obter token do core: " + e.getClass().getSimpleName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProjectionException("Interrompido ao obter token do core");
    }
  }

  private void fetch(String url) throws IOException, InterruptedException {
    StringBuilder form = new StringBuilder("grant_type=client_credentials");
    form.append("&client_id=").append(encode(config.core().clientId()));
    config.core().clientSecret().ifPresent(s -> form.append("&client_secret=").append(encode(s)));
    config.core().scope().ifPresent(s -> form.append("&scope=").append(encode(s)));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new ProjectionException("Servidor de autorização respondeu " + response.statusCode());
    }
    JsonNode body = json.readTree(response.body());
    String access = body.path("access_token").asText(null);
    if (access == null || access.isBlank()) {
      throw new ProjectionException("Resposta do servidor de autorização sem access_token");
    }
    long expiresIn = body.path("expires_in").asLong(300);
    token = access;
    expiresAt = Instant.now().plusSeconds(Math.max(expiresIn - 30, 5));
    LOG.debugf("Token client-credentials renovado (expira em %ds)", expiresIn);
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
