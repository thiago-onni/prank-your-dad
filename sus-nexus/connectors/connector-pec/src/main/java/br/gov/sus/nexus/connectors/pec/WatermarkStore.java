package br.gov.sus.nexus.connectors.pec;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Marca d'água por entidade (último {@code dt_atualizado} sincronizado), persistida em JSON. */
public final class WatermarkStore {

  private final Path file;
  private final ObjectMapper mapper;
  private final String initial;
  private final Map<String, String> values = new HashMap<>();

  public WatermarkStore(Path file, ObjectMapper mapper, String initial) {
    this.file = file;
    this.mapper = mapper;
    this.initial = initial;
    load();
  }

  @SuppressWarnings("unchecked")
  private void load() {
    if (!Files.exists(file)) return;
    try {
      values.putAll(mapper.readValue(file.toFile(), Map.class));
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao ler marca d'água " + file, e);
    }
  }

  public synchronized String get(String entity) {
    return values.getOrDefault(entity, initial);
  }

  public synchronized void advance(String entity, String value) {
    if (value == null || value.isBlank()) return;
    String current = values.get(entity);
    if (current == null || value.compareTo(current) > 0) {
      values.put(entity, value);
      persist();
    }
  }

  private void persist() {
    try {
      if (file.getParent() != null) Files.createDirectories(file.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), values);
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao gravar marca d'água " + file, e);
    }
  }
}
