package br.gov.sus.nexus.connectors.esusreg;

import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Cliente JSON genérico e paginado da API do e-SUS Regulação. Percorre páginas por número ({@code
 * pagination=page}) ou cursor ({@code pagination=cursor}) a partir de {@code updated_since} e
 * entrega cada item ao consumidor. Caminhos JSON usam a notação {@code a.b[0].c}.
 */
public final class EsusApiClient {

  private static final Logger LOG = Logger.getLogger(EsusApiClient.class);
  private static final int MAX_PAGES = 10_000;

  private final EsusConfig.Api config;
  private final HttpClient http;
  private final ObjectMapper mapper;
  private final OAuth2TokenProvider tokens;

  public EsusApiClient(
      EsusConfig.Api config, HttpClient http, ObjectMapper mapper, OAuth2TokenProvider tokens) {
    this.config = config;
    this.http = http;
    this.mapper = mapper;
    this.tokens = tokens;
  }

  /** Lista todos os itens atualizados desde {@code updatedSince}; retorna o total entregue. */
  public int listSince(String path, String updatedSince, Consumer<JsonNode> consumer) {
    int delivered = 0;
    boolean cursorMode = "cursor".equalsIgnoreCase(config.pagination());
    int page = config.firstPage();
    String cursor = null;
    for (int i = 0; i < MAX_PAGES; i++) {
      Map<String, String> query = new LinkedHashMap<>();
      query.put(config.updatedSinceParam(), updatedSince);
      query.put(config.pageSizeParam(), String.valueOf(config.pageSize()));
      if (cursorMode) {
        if (cursor != null) query.put(config.cursorParam(), cursor);
      } else {
        query.put(config.pageParam(), String.valueOf(page));
      }
      JsonNode body = get(path, query);
      List<JsonNode> items = itemsOf(body);
      items.forEach(consumer);
      delivered += items.size();
      if (cursorMode) {
        JsonNode next = JsonPaths.at(body, config.nextCursorPath());
        if (next == null || next.isNull() || next.asText().isBlank()) break;
        cursor = next.asText();
      } else {
        if (items.size() < config.pageSize()) break;
        page++;
      }
    }
    return delivered;
  }

  public JsonNode get(String path, Map<String, String> query) {
    StringBuilder url = new StringBuilder(config.baseUrl());
    if (!path.startsWith("/") && !url.toString().endsWith("/")) url.append('/');
    url.append(path);
    if (!query.isEmpty()) {
      url.append(path.contains("?") ? '&' : '?');
      List<String> params = new ArrayList<>();
      query.forEach(
          (k, v) ->
              params.add(
                  URLEncoder.encode(k, StandardCharsets.UTF_8)
                      + "="
                      + URLEncoder.encode(v, StandardCharsets.UTF_8)));
      url.append(String.join("&", params));
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url.toString()))
            .timeout(Duration.ofMillis(config.timeoutMs()))
            .header("Accept", "application/json")
            .GET();
    tokens.accessToken().ifPresent(t -> request.header("Authorization", "Bearer " + t));
    try {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == 401 || status == 403) {
        tokens.invalidate();
        throw ConnectorException.transientError(
            "ingest", "e-SUS Regulação respondeu " + status + " em " + path, null);
      }
      if (status >= 500 || status == 429 || status == 408) {
        throw ConnectorException.transientError(
            "ingest", "e-SUS Regulação respondeu " + status + " em " + path, null);
      }
      if (status / 100 != 2) {
        throw ConnectorException.permanent(
            "ingest",
            "e-SUS Regulação respondeu "
                + status
                + " em "
                + path
                + ": "
                + Pii.maskText(response.body()),
            null);
      }
      return mapper.readTree(response.body());
    } catch (IOException e) {
      LOG.warnf("falha de rede ao chamar e-SUS Regulação: %s", Pii.maskText(String.valueOf(e)));
      throw ConnectorException.transientError("ingest", "falha de rede: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.transientError("ingest", "interrompido", e);
    }
  }

  private List<JsonNode> itemsOf(JsonNode body) {
    JsonNode items = config.itemsPath().isBlank() ? body : JsonPaths.at(body, config.itemsPath());
    List<JsonNode> out = new ArrayList<>();
    if (items != null && items.isArray()) items.forEach(out::add);
    else if (body.isArray()) body.forEach(out::add);
    return out;
  }
}
