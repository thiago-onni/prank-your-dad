package br.gov.sus.nexus.core.platform.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Criptografia de campo (AES-256-GCM) para identificadores de alto risco ({@code value_enc}).
 * Formato: {@code [12 bytes IV][ciphertext+tag]}. A chave vem de {@code sus.identity.enc-key}
 * (base64, 32 bytes); em produção deve vir de um cofre (OpenBao transit).
 */
@ApplicationScoped
public class FieldCipher {

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  public FieldCipher(@ConfigProperty(name = "sus.identity.enc-key") String base64Key) {
    byte[] raw = Base64.getDecoder().decode(base64Key.trim());
    if (raw.length != 32) {
      throw new IllegalStateException("sus.identity.enc-key deve ter 32 bytes (AES-256)");
    }
    this.key = new SecretKeySpec(raw, "AES");
  }

  public byte[] encrypt(String plaintext) {
    try {
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[IV_BYTES + ct.length];
      System.arraycopy(iv, 0, out, 0, IV_BYTES);
      System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
      return out;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("falha ao cifrar campo", e);
    }
  }

  public String decrypt(byte[] blob) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
      byte[] pt = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
      return new String(pt, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("falha ao decifrar campo", e);
    }
  }
}
