package br.gov.sus.nexus.fhir.interaction;

import java.security.SecureRandom;

/** Gerador de ULID (26 caracteres Crockford base32), válido como id FHIR e ordenável no tempo. */
public final class IdGenerator {

  private IdGenerator() {}

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  public static String ulid() {
    long time = System.currentTimeMillis();
    byte[] random = new byte[10];
    RANDOM.nextBytes(random);
    char[] out = new char[26];
    // 48 bits de tempo → 10 caracteres
    for (int i = 9; i >= 0; i--) {
      out[i] = ALPHABET[(int) (time & 0x1F)];
      time >>>= 5;
    }
    // 80 bits aleatórios → 16 caracteres
    long hi = 0;
    for (int i = 0; i < 5; i++) {
      hi = (hi << 8) | (random[i] & 0xFF);
    }
    long lo = 0;
    for (int i = 5; i < 10; i++) {
      lo = (lo << 8) | (random[i] & 0xFF);
    }
    for (int i = 17; i >= 10; i--) {
      out[i] = ALPHABET[(int) (hi & 0x1F)];
      hi >>>= 5;
    }
    for (int i = 25; i >= 18; i--) {
      out[i] = ALPHABET[(int) (lo & 0x1F)];
      lo >>>= 5;
    }
    return new String(out);
  }
}
