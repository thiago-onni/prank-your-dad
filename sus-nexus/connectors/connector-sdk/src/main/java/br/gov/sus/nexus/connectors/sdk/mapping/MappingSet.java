package br.gov.sus.nexus.connectors.sdk.mapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Conjunto nomeado de versões de mapeamento; o conector seleciona pela versão do descriptor. */
public final class MappingSet {

  private final String name;
  private final Map<String, MappingVersion> versions = new LinkedHashMap<>();

  public MappingSet(String name, List<MappingVersion> versions) {
    this.name = name;
    for (MappingVersion v : versions) {
      if (!name.equals(v.mappingSet())) {
        throw new IllegalArgumentException(
            "versão " + v.version() + " pertence ao set " + v.mappingSet() + ", não " + name);
      }
      this.versions.put(v.version(), v);
    }
  }

  public String name() {
    return name;
  }

  public Optional<MappingVersion> find(String version) {
    return Optional.ofNullable(versions.get(version));
  }

  public MappingVersion require(String version) {
    return find(version)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "mapping set '"
                        + name
                        + "' não possui versão "
                        + version
                        + "; disponíveis: "
                        + versions.keySet()));
  }

  public Map<String, MappingVersion> versions() {
    return Collections.unmodifiableMap(versions);
  }
}
