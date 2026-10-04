package br.gov.sus.nexus.connectors.rnds;

import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Envio do Bundle ao serviço EHR da RNDS: {@code POST <ehr>/api/fhir/r4/Bundle} com {@code
 * X-Authorization-Server: Bearer <access_token>} e {@code Authorization: <CNS do profissional>}
 * (Guia de Integração, "Conheça os serviços"; coleção Postman oficial do DATASUS v4). Aceite = 201
 * com o id atribuído pela RNDS no header {@code Location}/{@code Content-Location} ({@code
 * .../r4/Bundle/<id>}). Falhas de rede/timeout viram erro transitório.
 */
@ApplicationScoped
public class RndsEhrClient {

  /** Resposta crua do EHR (corpo pode ser {@code OperationOutcome}). */
  public record EhrResponse(int status, Optional<String> location, String body) {

    /**
     * Id atribuído pela RNDS ao documento: segmento após a última "/" do {@code Location} (guia,
     * "Homologar": {@code .../r4/Bundle/eb4fc099-...-r3x7}).
     */
    public Optional<String> rndsId() {
      return location
          .map(String::trim)
          .map(l -> l.endsWith("/") ? l.substring(0, l.length() - 1) : l)
          .map(l -> l.substring(l.lastIndexOf('/') + 1))
          .filter(id -> !id.isBlank());
    }
  }

  private final RndsConfig config;
  private final RndsAuthClient auth;

  @Inject
  public RndsEhrClient(RndsConfig config, RndsAuthClient auth) {
    this.config = config;
    this.auth = auth;
  }

  public EhrResponse post(String ehrPath, String bundleJson) {
    String requester =
        config
            .requesterCns()
            .map(Documents::digits)
            .filter(c -> !c.isBlank())
            .orElseThrow(
                () ->
                    ConnectorException.transientError(
                        "publish", "rnds.requester-cns não configurado", null));
    String token = auth.token();
    String scheme = config.headers().tokenScheme();
    String tokenValue = scheme == null || scheme.isBlank() ? token : scheme.trim() + " " + token;
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(join(RndsEndpoints.ehrUrl(config), ehrPath)))
            .timeout(config.timeout())
            .header("Content-Type", config.headers().contentType())
            .header("Accept", "application/fhir+json, application/json")
            .header(config.headers().token(), tokenValue)
            .header(config.headers().requester(), requester)
            .POST(HttpRequest.BodyPublishers.ofString(bundleJson, StandardCharsets.UTF_8))
            .build();
    try {
      HttpResponse<String> response =
          auth.http().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new EhrResponse(
          response.statusCode(),
          response
              .headers()
              .firstValue("Location")
              .or(() -> response.headers().firstValue("Content-Location")),
          response.body() == null ? "" : response.body());
    } catch (IOException e) {
      throw ConnectorException.transientError(
          "publish", "falha de rede/timeout no envio à RNDS: " + Pii.maskText(e.getMessage()), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.transientError("publish", "interrompido", e);
    }
  }

  static String join(String base, String path) {
    if (path == null || path.isBlank()) return base;
    boolean slashBase = base.endsWith("/");
    boolean slashPath = path.startsWith("/");
    if (slashBase && slashPath) return base + path.substring(1);
    if (!slashBase && !slashPath) return base + "/" + path;
    return base + path;
  }
}
