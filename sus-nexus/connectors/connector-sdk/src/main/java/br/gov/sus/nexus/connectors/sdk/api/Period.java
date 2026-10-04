package br.gov.sus.nexus.connectors.sdk.api;

import java.time.Duration;
import java.time.Instant;

/** Intervalo fechado-aberto [start, end). */
public record Period(Instant start, Instant end) {

  public Period {
    if (start == null || end == null || !end.isAfter(start)) {
      throw new IllegalArgumentException("Período inválido: " + start + " .. " + end);
    }
  }

  public static Period last(Duration duration) {
    Instant now = Instant.now();
    return new Period(now.minus(duration), now);
  }

  public boolean contains(Instant instant) {
    return instant != null && !instant.isBefore(start) && instant.isBefore(end);
  }
}
