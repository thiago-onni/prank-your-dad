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
  public static final String APPOINTMENT = "apt_";
  public static final String APPOINTMENT_HISTORY = "ash_";
  public static final String APPOINTMENT_DUPLICATE = "dup_";
  public static final String TASK = "task_";
  public static final String TASK_HISTORY = "th_";
  public static final String INTEGRATION_MESSAGE = "msg_";
  public static final String INTEGRATION_ERROR = "ierr_";
  public static final String DEAD_LETTER = "dlq_";
  public static final String RECONCILIATION = "rec_";
  public static final String TIMELINE_EVENT = "tle_";
  public static final String REGULATION_REQUEST = "reg_";
  public static final String REGULATION_HISTORY = "rsh_";
  public static final String REGULATION_DECISION = "rdec_";
  public static final String REGULATION_ISSUE = "ris_";
  public static final String PROVIDER_CAPACITY = "cap_";
  public static final String EXAM_ORDER = "exo_";
  public static final String EXAM_HISTORY = "esh_";
  public static final String EXAM_RESULT = "exr_";
  public static final String HOSPITAL_EPISODE = "hep_";
  public static final String BED_MOVEMENT = "hbm_";
  public static final String DISCHARGE = "hdis_";
  public static final String COUNTER_REFERRAL = "cref_";
  public static final String CARE_PLAN = "cp_";
  public static final String CARE_PLAN_ITEM = "cpi_";
  public static final String CARE_GAP = "gap_";
  public static final String PROTOCOL = "prot_";
  public static final String PROTOCOL_VERSION = "pv_";
  public static final String RULE_VERSION = "rv_";
  public static final String CONSENT = "cons_";
  public static final String COMMUNICATION_PREFERENCE = "cpref_";
  public static final String PRODUCTION_RECORD = "prod_";
  public static final String PRODUCTION_HISTORY = "prh_";
  public static final String PRODUCTION_ISSUE = "pis_";
  public static final String PRODUCTION_BATCH = "pbat_";
  public static final String PRODUCTION_BATCH_ITEM = "pbi_";
  public static final String PRODUCTION_SUBMISSION = "psub_";
  public static final String PRODUCTION_OUTCOME = "pout_";

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
