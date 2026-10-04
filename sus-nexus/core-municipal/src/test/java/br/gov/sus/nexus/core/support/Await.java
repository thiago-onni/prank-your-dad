package br.gov.sus.nexus.core.support;

import java.time.Duration;
import java.util.function.Supplier;

/** Espera ativa simples para efeitos assíncronos (consumidores in-memory, workflows). */
public final class Await {

  private Await() {}

  public static void until(String what, Supplier<Boolean> condition) {
    until(what, Duration.ofSeconds(15), condition);
  }

  public static void until(String what, Duration timeout, Supplier<Boolean> condition) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      try {
        if (Boolean.TRUE.equals(condition.get())) {
          return;
        }
      } catch (RuntimeException e) {
        // tenta de novo
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("tempo esgotado aguardando: " + what);
  }
}
