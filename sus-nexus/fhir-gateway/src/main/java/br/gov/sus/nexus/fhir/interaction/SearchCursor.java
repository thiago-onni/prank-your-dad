package br.gov.sus.nexus.fhir.interaction;

import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cursor de paginação opaco e assinado (HMAC-SHA256): carrega tipo, parâmetros originais, último id
 * da página e tamanho. Evita adulteração de parâmetros entre páginas.
 */
@ApplicationScoped
public class SearchCursor {

  /** Conteúdo do cursor. */
  public record Payload(String type, Map<String, List<String>> params, String afterId, int count) {}

  private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();

  @Inject FhirGatewayConfig config;

  private final ObjectMapper mapper = new ObjectMapper();

  public String encode(Payload payload) {
    try {
      byte[] json = mapper.writeValueAsBytes(payload);
      String body = B64E.encodeToString(json);
      return body + "." + B64E.encodeToString(sign(body));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Falha ao codificar cursor", e);
    }
  }

  public Payload decode(String cursor) {
    if (cursor == null || !cursor.contains(".")) {
      throw FhirException.invalid("Cursor de paginação inválido", "_cursor");
    }
    int dot = cursor.lastIndexOf('.');
    String body = cursor.substring(0, dot);
    byte[] expected = sign(body);
    byte[] given;
    try {
      given = B64D.decode(cursor.substring(dot + 1));
    } catch (IllegalArgumentException e) {
      throw FhirException.invalid("Cursor de paginação inválido", "_cursor");
    }
    if (!MessageDigest.isEqual(expected, given)) {
      throw FhirException.invalid("Cursor de paginação inválido ou adulterado", "_cursor");
    }
    try {
      return mapper.readValue(B64D.decode(body), Payload.class);
    } catch (java.io.IOException | IllegalArgumentException e) {
      throw FhirException.invalid("Cursor de paginação inválido", "_cursor");
    }
  }

  private byte[] sign(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              config.search().cursorSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC indisponível", e);
    }
  }
}
