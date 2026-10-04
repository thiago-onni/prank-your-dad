package br.gov.sus.nexus.connectors.sdk.api;

import java.util.Map;

/** Pedido de ingestão: modo (pull, push, file) e parâmetros (ex.: período, caminho). */
public record IngestRequest(Mode mode, Period period, Map<String, String> parameters) {

  public enum Mode {
    PULL,
    PUSH,
    FILE
  }

  public IngestRequest {
    parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
  }

  public static IngestRequest file(String path) {
    return new IngestRequest(Mode.FILE, null, Map.of("path", path));
  }

  public static IngestRequest pull(Period period) {
    return new IngestRequest(Mode.PULL, period, Map.of());
  }
}
