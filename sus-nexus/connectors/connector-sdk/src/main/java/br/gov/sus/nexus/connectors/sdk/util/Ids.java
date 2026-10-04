package br.gov.sus.nexus.connectors.sdk.util;

import com.github.f4b6a3.ulid.UlidCreator;

/** Geração de IDs ULID prefixados (CONVENTIONS.md). */
public final class Ids {

  private Ids() {}

  public static String ulid() {
    return UlidCreator.getUlid().toString();
  }

  public static String message() {
    return "msg_" + ulid();
  }

  public static String deadLetter() {
    return "dlq_" + ulid();
  }

  public static String correlation() {
    return "corr_" + ulid();
  }
}
