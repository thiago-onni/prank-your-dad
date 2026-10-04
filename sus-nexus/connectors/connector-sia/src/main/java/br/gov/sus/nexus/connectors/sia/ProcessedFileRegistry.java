package br.gov.sus.nexus.connectors.sia;

import br.gov.sus.nexus.connectors.sdk.api.Period;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Marca d'água por arquivo: SHA-256 de cada arquivo processado (nome, kind, entidade, instante,
 * linhas) em JSON. Mesmo conteúdo, ainda que renomeado, não é relido. Alimenta também o lado
 * "fonte" da reconciliação (linhas lidas por entidade no período).
 */
public final class ProcessedFileRegistry {

  /** Entrada do registro. */
  public record Entry(String fileName, String kind, String entity, String processedAt, int rows) {}

  private final Path file;
  private final ObjectMapper mapper;
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  public ProcessedFileRegistry(Path file, ObjectMapper mapper) {
    this.file = file;
    this.mapper = mapper;
    load();
  }

  private void load() {
    if (!Files.exists(file)) return;
    try {
      entries.putAll(mapper.readValue(file.toFile(), new TypeReference<Map<String, Entry>>() {}));
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao ler registro de arquivos " + file, e);
    }
  }

  public synchronized boolean isProcessed(String sha256) {
    return entries.containsKey(sha256);
  }

  public synchronized void markProcessed(
      String sha256, String fileName, String kind, String entity, int rows) {
    entries.put(sha256, new Entry(fileName, kind, entity, Instant.now().toString(), rows));
    try {
      if (file.getParent() != null) Files.createDirectories(file.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao gravar registro de arquivos " + file, e);
    }
  }

  /** Linhas lidas da entidade em arquivos processados no período (lado "fonte"). */
  public synchronized long rows(String entity, Period period) {
    return entries.values().stream()
        .filter(e -> entity.equals(e.entity()))
        .filter(e -> period == null || period.contains(Instant.parse(e.processedAt())))
        .mapToLong(Entry::rows)
        .sum();
  }

  public synchronized int size() {
    return entries.size();
  }

  /** Esquece todos os arquivos (reprocessamento total; testes e operação assistida). */
  public synchronized void clear() {
    entries.clear();
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao apagar registro de arquivos " + file, e);
    }
  }
}
