package br.gov.sus.nexus.connectors.sisreg;

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
 * Marca d'água por arquivo: registra o SHA-256 de cada exportação processada (nome, instante,
 * linhas) em JSON. Um arquivo com o mesmo conteúdo, ainda que renomeado, não é reprocessado.
 */
public final class ProcessedFileRegistry {

  /** Entrada do registro. */
  public record Entry(String fileName, String processedAt, int rows) {}

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

  public synchronized void markProcessed(String sha256, String fileName, int rows) {
    entries.put(sha256, new Entry(fileName, Instant.now().toString(), rows));
    try {
      if (file.getParent() != null) Files.createDirectories(file.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao gravar registro de arquivos " + file, e);
    }
  }

  public synchronized int size() {
    return entries.size();
  }

  /** Esquece todos os arquivos (reprocessamento total; usado em testes e operação assistida). */
  public synchronized void clear() {
    entries.clear();
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao apagar registro de arquivos " + file, e);
    }
  }
}
