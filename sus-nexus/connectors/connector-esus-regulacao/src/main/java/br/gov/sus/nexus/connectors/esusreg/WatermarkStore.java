package br.gov.sus.nexus.connectors.esusreg;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Marca d'água por entidade (maior {@code atualizado_em} sincronizado), persistida em JSON. */
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

  private void load() {
    if (!Files.exists(file)) return;
    try {
      values.putAll(mapper.readValue(file.toFile(), new TypeReference<Map<String, String>>() {}));
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao ler marca d'água " + file, e);
    }
  }

  public synchronized String get(String entity) {
    return values.getOrDefault(entity, initial);
  }

  /** Avança se {@code value} for lexicograficamente maior (ISO-8601 ordena como texto). */
  public synchronized void advance(String entity, String value) {
    if (value == null || value.isBlank()) return;
    String current = values.get(entity);
    if (current == null || value.compareTo(current) > 0) {
      values.put(entity, value);
      persist();
    }
  }

  public synchronized void reset() {
    values.clear();
    persist();
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
