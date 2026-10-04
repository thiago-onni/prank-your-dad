package br.gov.sus.nexus.fhir.binary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Armazenamento em sistema de arquivos (desenvolvimento e testes). Chave = caminho relativo. */
public class FileBinaryStorage implements BinaryStorage {

  private final Path baseDir;

  public FileBinaryStorage(Path baseDir) {
    this.baseDir = baseDir.toAbsolutePath().normalize();
  }

  @Override
  public String put(String tenantId, String id, String contentType, byte[] content) {
    String key = tenantId + "/" + id;
    Path target = resolve(key);
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new BinaryStorageException("Falha ao gravar conteúdo no armazenamento de arquivos", e);
    }
    return key;
  }

  @Override
  public Optional<byte[]> get(String key) {
    Path target = resolve(key);
    if (!Files.isRegularFile(target)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readAllBytes(target));
    } catch (IOException e) {
      throw new BinaryStorageException("Falha ao ler conteúdo do armazenamento de arquivos", e);
    }
  }

  private Path resolve(String key) {
    Path p = baseDir.resolve(key).normalize();
    if (!p.startsWith(baseDir)) {
      throw new BinaryStorageException("Chave de armazenamento inválida", null);
    }
    return p;
  }

  @Override
  public String describe() {
    return "file:" + baseDir;
  }
}
