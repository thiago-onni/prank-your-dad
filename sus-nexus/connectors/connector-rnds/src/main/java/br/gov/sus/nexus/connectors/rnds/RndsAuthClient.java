package br.gov.sus.nexus.connectors.rnds;

import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Token da RNDS obtido no serviço de autenticação com o certificado digital ICP-Brasil (e-CNPJ)
 * apresentado via mTLS. O token fica em cache até expirar (menos {@code rnds.auth.refresh-skew}). O
 * mesmo {@link HttpClient} (com o certificado) é usado no envio ao EHR.
 */
@ApplicationScoped
public class RndsAuthClient {

  private static final Logger LOG = Logger.getLogger(RndsAuthClient.class);

  private final RndsConfig config;
  private final ObjectMapper mapper;
  private volatile HttpClient http;
  private volatile String token;
  private volatile Instant expiresAt = Instant.EPOCH;
  private volatile int tokenRequests;

  @Inject
  public RndsAuthClient(RndsConfig config, ObjectMapper mapper) {
    this.config = config;
    this.mapper = mapper;
  }

  /** Cliente HTTP com o certificado de cliente (criado sob demanda). */
  public HttpClient http() {
    HttpClient client = http;
    if (client == null) {
      synchronized (this) {
        if (http == null) {
          try {
            http =
                HttpClient.newBuilder()
                    .sslContext(MtlsSupport.sslContext(config.certificate()))
                    .connectTimeout(config.timeout())
                    .build();
          } catch (IllegalStateException e) {
            // certificado ausente/ilegível: configuração corrigível → transitório (retry/DLQ)
            throw ConnectorException.transientError("authenticate", e.getMessage(), e);
          }
        }
        client = http;
      }
    }
    return client;
  }

  /** Token vigente (do cache ou recém-obtido). */
  public synchronized String token() {
    if (token != null && Instant.now().isBefore(expiresAt)) return token;
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(config.authUrl()))
            .timeout(config.timeout())
            .header("Accept", "application/json");
    if ("POST".equalsIgnoreCase(config.auth().method())) {
      builder.POST(HttpRequest.BodyPublishers.noBody());
    } else {
      builder.GET();
    }
    HttpResponse<String> response;
    try {
      tokenRequests++;
      response = http().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw ConnectorException.transientError(
          "authenticate",
          "falha mTLS/rede no serviço de autenticação da RNDS: " + Pii.maskText(e.getMessage()),
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.transientError("authenticate", "interrompido", e);
    }
    int status = response.statusCode();
    if (status / 100 != 2) {
      // 4xx no token costuma ser certificado não habilitado/revogado: operador precisa agir,
      // mas mantemos transitório para não perder o envio enquanto o certificado é corrigido.
      throw ConnectorException.transientError(
          "authenticate", "serviço de autenticação da RNDS respondeu " + status, null);
    }
    try {
      JsonNode body = mapper.readTree(response.body());
      String access = body.path(config.auth().tokenField()).asText(null);
      if (access == null || access.isBlank()) {
        throw ConnectorException.transientError(
            "authenticate", "resposta de autenticação sem " + config.auth().tokenField(), null);
      }
      Duration ttl =
          body.hasNonNull(config.auth().expiresInField())
              ? Duration.ofSeconds(body.path(config.auth().expiresInField()).asLong())
              : config.auth().defaultTtl();
      Duration effective = ttl.minus(config.auth().refreshSkew());
      if (effective.isNegative() || effective.isZero()) effective = Duration.ofSeconds(5);
      token = access;
      expiresAt = Instant.now().plus(effective);
      LOG.debugf("token RNDS obtido (validade %s)", ttl);
      return token;
    } catch (IOException e) {
      throw ConnectorException.transientError("authenticate", "token RNDS ilegível", e);
    }
  }

  /** Descarta o token (ex.: após 401 do EHR). */
  public synchronized void invalidate() {
    token = null;
    expiresAt = Instant.EPOCH;
  }

  public boolean hasValidToken() {
    return token != null && Instant.now().isBefore(expiresAt);
  }

  /** Quantas vezes o serviço de token foi chamado (diagnóstico/testes). */
  public int tokenRequests() {
    return tokenRequests;
  }
}
