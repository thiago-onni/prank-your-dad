package br.gov.sus.nexus.connectors.sdk.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 em hexadecimal minúsculo. */
public final class Hashes {

  private Hashes() {}

  public static String sha256Hex(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponível", e);
    }
  }

  public static String sha256Hex(String text) {
    return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
  }
}
