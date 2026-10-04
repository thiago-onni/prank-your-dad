package br.gov.sus.nexus.connectors.sdk.api;

import java.util.List;
import java.util.Map;

/** Capacidades descobertas na fonte real (podem ser subconjunto do descriptor). */
public record Capabilities(
    List<String> entities, List<String> modes, String sourceVersion, Map<String, String> features) {

  public Capabilities {
    entities = List.copyOf(entities);
    modes = List.copyOf(modes);
    features = features == null ? Map.of() : Map.copyOf(features);
  }
}
