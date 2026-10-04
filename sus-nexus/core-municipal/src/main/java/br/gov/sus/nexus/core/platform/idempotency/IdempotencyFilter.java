package br.gov.sus.nexus.core.platform.idempotency;

import br.gov.sus.nexus.core.platform.errors.ProblemDetails;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Idempotência de API (CONVENTIONS.md): POST com header {@code Idempotency-Key} tem a resposta
 * armazenada em {@code platform.idempotency_key} por tenant durante 72 h. Mesma chave + mesmo hash
 * da requisição → devolve a resposta armazenada ({@code Idempotent-Replayed: true}); mesma chave +
 * requisição diferente → 422. O hash cobre método, caminho e corpo.
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 100)
public class IdempotencyFilter implements ContainerRequestFilter, ContainerResponseFilter {

  public static final String HEADER = "Idempotency-Key";
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";
  private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
  private static final String PROP_KEY = IdempotencyFilter.class.getName() + ".key";
  private static final String PROP_HASH = IdempotencyFilter.class.getName() + ".hash";
  private static final Logger LOG = Logger.getLogger(IdempotencyFilter.class);

  @Inject IdempotencyStore store;
  @Inject TenantContext tenantContext;
  @Inject ObjectMapper objectMapper;

  @Override
  public void filter(ContainerRequestContext request) throws IOException {
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return;
    }
    String key = request.getHeaderString(HEADER);
    if (key == null || key.isBlank()) {
      return;
    }
    key = key.trim();
    if (!SAFE_KEY.matcher(key).matches()) {
      request.abortWith(
          problem(400, "Requisição inválida", "Idempotency-Key inválida", "bad-request"));
      return;
    }
    if (!tenantContext.isPresent()) {
      return; // o TenantFilter já abortou a requisição
    }
    byte[] body = request.hasEntity() ? request.getEntityStream().readAllBytes() : new byte[0];
    request.setEntityStream(new ByteArrayInputStream(body));
    String hash = hash(request.getMethod(), request.getUriInfo().getPath(), body);

    Optional<IdempotencyStore.Stored> stored = store.find(key);
    if (stored.isPresent()) {
      IdempotencyStore.Stored s = stored.get();
      if (!s.requestHash().equals(hash)) {
        request.abortWith(
            problem(
                422,
                "Dados inválidos",
                "Idempotency-Key já usada com uma requisição diferente",
                "idempotency-key-reused"));
        return;
      }
      Response.ResponseBuilder replay =
          Response.status(s.statusCode()).header(REPLAYED_HEADER, "true");
      if (s.responseBody() != null) {
        replay.type(MediaType.APPLICATION_JSON).entity(s.responseBody());
      }
      request.abortWith(replay.build());
      return;
    }
    request.setProperty(PROP_KEY, key);
    request.setProperty(PROP_HASH, hash);
  }

  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    Object key = request.getProperty(PROP_KEY);
    Object hash = request.getProperty(PROP_HASH);
    if (key == null || hash == null) {
      return;
    }
    int status = response.getStatus();
    if (status >= 500) {
      return; // falhas transitórias não são memorizadas
    }
    String body = null;
    if (response.hasEntity()) {
      Object entity = response.getEntity();
      try {
        body =
            entity instanceof String s && looksLikeJson(s)
                ? s
                : objectMapper.writeValueAsString(entity);
      } catch (JsonProcessingException e) {
        LOG.warn("resposta não serializável para idempotência; não armazenada");
        return;
      }
    }
    try {
      store.save(key.toString(), hash.toString(), status, body);
    } catch (RuntimeException e) {
      // corrida entre requisições concorrentes com a mesma chave: a primeira vence
      LOG.debugf("idempotency_key não armazenada: %s", e.getClass().getSimpleName());
    }
  }

  static String hash(String method, String path, byte[] body) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(method.toUpperCase().getBytes(StandardCharsets.UTF_8));
      md.update((byte) '\n');
      md.update(path.getBytes(StandardCharsets.UTF_8));
      md.update((byte) '\n');
      md.update(body);
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean looksLikeJson(String s) {
    String t = s.trim();
    return t.startsWith("{") || t.startsWith("[");
  }

  private static Response problem(int status, String title, String detail, String code) {
    Object corr = MDC.get("correlation_id");
    ProblemDetails body =
        new ProblemDetails(
            "urn:sus-nexus:problem:" + code,
            title,
            status,
            detail,
            null,
            corr != null ? corr.toString() : null,
            null);
    return Response.status(status).type(ProblemDetails.MEDIA_TYPE).entity(body).build();
  }
}
