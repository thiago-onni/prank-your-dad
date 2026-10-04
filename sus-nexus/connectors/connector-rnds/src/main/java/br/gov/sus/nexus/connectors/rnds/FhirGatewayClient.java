package br.gov.sus.nexus.connectors.rnds;

import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.config.ConnectorConfig;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
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
import java.time.Instant;
import org.hl7.fhir.r4.formats.JsonParser;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;

/**
 * Leitura de recursos no fhir-gateway municipal ({@code GET /fhir/r4/...}) com token OAuth2 client
 * credentials (escopo {@code system/*.read}, cache até expirar). 404/403 são permanentes (DLQ); 401
 * descarta o token e é transitório; 5xx e rede são transitórios (retry do pipeline).
 */
@ApplicationScoped
public class FhirGatewayClient {

  private final RndsConfig.Fhir config;
  private final String tenantId;
  private final ObjectMapper mapper;
  private final HttpClient http;
  private volatile String token;
  private volatile Instant tokenExpiresAt = Instant.EPOCH;

  @Inject
  public FhirGatewayClient(
      RndsConfig config, ConnectorConfig connectorConfig, ObjectMapper mapper) {
    this.config = config.fhir();
    this.tenantId = connectorConfig.tenantId();
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(this.config.timeout()).build();
  }

  /** {@code GET {base}/{type}/{id}}. */
  public Resource read(String type, String id) {
    return get(type + "/" + id);
  }

  /** {@code GET {base}/{type}?{param}={value}} (valor codificado). */
  public Bundle search(String type, String param, String value) {
    Resource r = get(type + "?" + param + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
    if (r instanceof Bundle b) return b;
    throw ConnectorException.permanent("transform", "busca " + type + " não retornou Bundle", null);
  }

  private Resource get(String path) {
    String base = config.baseUrl().endsWith("/") ? config.baseUrl() : config.baseUrl() + "/";
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(config.timeout())
            .header("Accept", "application/fhir+json")
            .header("X-Tenant-Id", tenantId)
            .header("X-Purpose-Of-Use", "integration_operations")
            .GET();
    accessToken().ifPresent(t -> builder.header("Authorization", "Bearer " + t));
    config.extraHeaders().forEach(builder::header);
    HttpResponse<String> response = send(builder.build(), "fhir-gateway " + typeOf(path));
    int status = response.statusCode();
    if (status == 401) {
      invalidate();
      throw ConnectorException.transientError(
          "transform", "fhir-gateway 401 (token renovado na próxima tentativa)", null);
    }
    if (status == 404 || status == 410 || status == 403) {
      throw ConnectorException.permanent(
          "transform", "fhir-gateway " + status + " em " + typeOf(path), null);
    }
    if (status / 100 != 2) {
      throw ConnectorException.transientError(
          "transform", "fhir-gateway respondeu " + status + " em " + typeOf(path), null);
    }
    try {
      return new JsonParser().parse(response.body());
    } catch (IOException | RuntimeException e) {
      throw ConnectorException.permanent(
          "transform", "recurso FHIR inválido em " + typeOf(path), e);
    }
  }

  private static String typeOf(String path) {
    int i = path.indexOf('/');
    int q = path.indexOf('?');
    int end = i > 0 ? i : (q > 0 ? q : path.length());
    return path.substring(0, end);
  }

  private java.util.Optional<String> accessToken() {
    return switch (config.authMode().toLowerCase()) {
      case "none" -> java.util.Optional.empty();
      case "static" -> config.staticToken().filter(t -> !t.isBlank());
      default -> java.util.Optional.of(clientCredentials());
    };
  }

  private synchronized String clientCredentials() {
    if (token != null && Instant.now().isBefore(tokenExpiresAt)) return token;
    String form =
        "grant_type=client_credentials&client_id="
            + URLEncoder.encode(config.clientId(), StandardCharsets.UTF_8)
            + "&client_secret="
            + URLEncoder.encode(config.clientSecret().orElse(""), StandardCharsets.UTF_8)
            + "&scope="
            + URLEncoder.encode(config.scope(), StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(config.tokenUrl()))
            .timeout(config.timeout())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    HttpResponse<String> response = send(request, "token do fhir-gateway");
    if (response.statusCode() / 100 != 2) {
      throw ConnectorException.transientError(
          "transform", "token do fhir-gateway: HTTP " + response.statusCode(), null);
    }
    try {
      JsonNode body = mapper.readTree(response.body());
      String access = body.path("access_token").asText(null);
      if (access == null) {
        throw ConnectorException.transientError("transform", "token sem access_token", null);
      }
      token = access;
      tokenExpiresAt =
          Instant.now().plusSeconds(Math.max(10, body.path("expires_in").asLong(300) - 30));
      return token;
    } catch (IOException e) {
      throw ConnectorException.transientError("transform", "token ilegível", e);
    }
  }

  public synchronized void invalidate() {
    token = null;
    tokenExpiresAt = Instant.EPOCH;
  }

  private HttpResponse<String> send(HttpRequest request, String what) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw ConnectorException.transientError(
          "transform", "falha de rede (" + what + "): " + Pii.maskText(e.getMessage()), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.transientError("transform", "interrompido (" + what + ")", e);
    }
  }
}
