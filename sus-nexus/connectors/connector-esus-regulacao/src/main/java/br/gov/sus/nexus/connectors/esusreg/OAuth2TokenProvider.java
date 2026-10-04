package br.gov.sus.nexus.connectors.esusreg;

import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Token OAuth2 (client credentials) para a API do e-SUS Regulação, com cache até a expiração (menos
 * 30 s de folga). Modo {@code static} devolve o token configurado; {@code none} devolve vazio.
 */
public final class OAuth2TokenProvider {

  private final EsusConfig.Api config;
  private final HttpClient http;
  private final ObjectMapper mapper;
  private volatile String token;
  private volatile Instant expiresAt = Instant.EPOCH;

  public OAuth2TokenProvider(EsusConfig.Api config, HttpClient http, ObjectMapper mapper) {
    this.config = config;
    this.http = http;
    this.mapper = mapper;
  }

  public Optional<String> accessToken() {
    return switch (config.authMode().toLowerCase()) {
      case "none" -> Optional.empty();
      case "static" -> config.staticToken().filter(t -> !t.isBlank());
      default -> Optional.of(oauth2());
    };
  }

  private synchronized String oauth2() {
    if (token != null && Instant.now().isBefore(expiresAt)) return token;
    StringBuilder form =
        new StringBuilder("grant_type=client_credentials")
            .append("&client_id=")
            .append(URLEncoder.encode(config.clientId(), StandardCharsets.UTF_8))
            .append("&client_secret=")
            .append(URLEncoder.encode(config.clientSecret().orElse(""), StandardCharsets.UTF_8));
    config
        .scope()
        .filter(s -> !s.isBlank())
        .ifPresent(
            s -> form.append("&scope=").append(URLEncoder.encode(s, StandardCharsets.UTF_8)));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(config.tokenUrl()))
            .timeout(Duration.ofMillis(config.timeoutMs()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
            .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw ConnectorException.transientError(
            "authenticate", "token endpoint respondeu " + response.statusCode(), null);
      }
      JsonNode body = mapper.readTree(response.body());
      String access = body.path("access_token").asText(null);
      if (access == null) {
        throw ConnectorException.permanent("authenticate", "resposta sem access_token", null);
      }
      long expiresIn = body.path("expires_in").asLong(300);
      token = access;
      expiresAt = Instant.now().plusSeconds(Math.max(30, expiresIn - 30));
      return token;
    } catch (IOException e) {
      throw ConnectorException.transientError("authenticate", "falha ao obter token", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.transientError("authenticate", "interrompido", e);
    }
  }

  /** Descarta o token em cache (ex.: após 401). */
  public synchronized void invalidate() {
    token = null;
    expiresAt = Instant.EPOCH;
  }
}
