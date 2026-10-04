package br.gov.sus.nexus.core.sharedkernel;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * HMAC-SHA256 de identificadores de alto risco (CPF/CNS) para busca sem expor o valor. A chave é
 * derivada POR TENANT a partir da chave-mestra ({@code sus.identity.hmac-key}): {@code k_tenant =
 * HMAC(master, tenant_id)}; {@code hash = HMAC(k_tenant, system + ":" + valor_normalizado)}.
 */
@ApplicationScoped
public class IdentifierHash {

  private static final String ALG = "HmacSHA256";
  private final byte[] masterKey;

  public IdentifierHash(@ConfigProperty(name = "sus.identity.hmac-key") String key) {
    this.masterKey = decodeKey(key);
  }

  /** Hash hexadecimal (64 chars) de um identificador para o tenant. */
  public String hash(String tenantId, String system, String value) {
    byte[] tenantKey = hmac(masterKey, tenantId.getBytes(StandardCharsets.UTF_8));
    String canonical = system.toUpperCase() + ":" + normalize(system, value);
    return HexFormat.of().formatHex(hmac(tenantKey, canonical.getBytes(StandardCharsets.UTF_8)));
  }

  /** Normalização canônica do valor por sistema (CPF/CNS: somente dígitos; demais: trim). */
  public static String normalize(String system, String value) {
    if (value == null) {
      return "";
    }
    return switch (system.toUpperCase()) {
      case "CPF", "CNS" -> value.replaceAll("[^0-9]", "");
      default -> value.trim();
    };
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance(ALG);
      mac.init(new SecretKeySpec(key, ALG));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC indisponível", e);
    }
  }

  private static byte[] decodeKey(String key) {
    String k = key == null ? "" : key.trim();
    if (k.length() < 32) {
      throw new IllegalStateException("sus.identity.hmac-key muito curta");
    }
    if (k.matches("^[0-9a-fA-F]+$") && k.length() % 2 == 0) {
      return HexFormat.of().parseHex(k);
    }
    try {
      return Base64.getDecoder().decode(k);
    } catch (IllegalArgumentException e) {
      return k.getBytes(StandardCharsets.UTF_8);
    }
  }
}
