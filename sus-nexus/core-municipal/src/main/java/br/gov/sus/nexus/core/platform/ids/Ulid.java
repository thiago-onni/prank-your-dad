package br.gov.sus.nexus.core.platform.ids;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Gerador de ULID (Crockford base32, 26 caracteres) com prefixo por tipo de entidade, conforme
 * CONVENTIONS.md: {@code cit_}, {@code cid_}, {@code evt_}, {@code case_} etc.
 */
public final class Ulid {

  public static final String CITIZEN = "cit_";
  public static final String CITIZEN_IDENTIFIER = "cid_";
  public static final String ORGANIZATION = "org_";
  public static final String HEALTH_UNIT = "hu_";
  public static final String PROFESSIONAL = "prof_";
  public static final String TEAM = "team_";
  public static final String EVENT = "evt_";
  public static final String MERGE_CASE = "case_";
  public static final String MERGE = "merge_";
  public static final String AUDIT = "aud_";
  public static final String ACCESS = "acc_";
  public static final String SOURCE_LINK = "link_";
  public static final String ADDRESS = "addr_";
  public static final String CONTACT = "ctt_";
  public static final String HISTORY = "hist_";
  public static final String MATCH_CANDIDATE = "mc_";
  public static final String MATCH_EVIDENCE = "me_";
  public static final String GOLDEN_ATTRIBUTE = "gra_";
  public static final String ROLE = "role_";
  public static final String TERRITORY = "terr_";
  public static final String MICROAREA = "micro_";

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final Pattern ULID_PATTERN = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
  private static final SecureRandom RANDOM = new SecureRandom();

  private Ulid() {}

  /** Gera um ULID puro (26 caracteres). */
  public static String generate() {
    long time = System.currentTimeMillis();
    byte[] rnd = new byte[10];
    RANDOM.nextBytes(rnd);
    char[] out = new char[26];
    // 48 bits de tempo em 10 caracteres
    for (int i = 9; i >= 0; i--) {
      out[i] = ALPHABET[(int) (time & 0x1F)];
      time >>>= 5;
    }
    // 80 bits aleatórios em 16 caracteres
    long hi = 0;
    for (int i = 0; i < 5; i++) {
      hi = (hi << 8) | (rnd[i] & 0xFF);
    }
    long lo = 0;
    for (int i = 5; i < 10; i++) {
      lo = (lo << 8) | (rnd[i] & 0xFF);
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

  /** Gera um ULID com o prefixo informado (ex.: {@code cit_}). */
  public static String generate(String prefix) {
    return prefix + generate();
  }

  /** Verifica se o valor tem a forma {@code <prefix><ULID>}. */
  public static boolean isValid(String prefix, String value) {
    return value != null
        && value.startsWith(prefix)
        && ULID_PATTERN.matcher(value.substring(prefix.length())).matches();
  }
}
