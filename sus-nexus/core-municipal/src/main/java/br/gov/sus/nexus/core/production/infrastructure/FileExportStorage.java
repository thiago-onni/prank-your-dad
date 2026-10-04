package br.gov.sus.nexus.core.production.infrastructure;

import br.gov.sus.nexus.core.production.application.ExportStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * {@link ExportStorage} em sistema de arquivos ({@code sus.production.export-dir}; padrão em
 * dev/test). O arquivo nunca é sobrescrito (CREATE_NEW): reexportar gera novo nome. Referência
 * {@code file://...}. Criado por {@link ExportStorageProducer}.
 */
public class FileExportStorage implements ExportStorage {

  private final Path root;

  public FileExportStorage(String exportDir) {
    this.root = Path.of(exportDir).toAbsolutePath().normalize();
  }

  @Override
  public StoredFile store(
      String tenantId, String competence, String batchId, String name, byte[] content) {
    ExportStorage.checkKey(tenantId, competence, batchId, name);
    try {
      Path dir = root.resolve(tenantId).resolve(competence).resolve(batchId);
      Files.createDirectories(dir);
      Path file = dir.resolve(name);
      Files.write(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      return new StoredFile(file.toUri().toString(), sha256(content), content.length);
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao gravar arquivo de exportação", e);
    }
  }

  @Override
  public byte[] read(String ref) {
    try {
      Path file = Path.of(URI.create(ref)).normalize();
      if (!file.startsWith(root)) {
        throw new IllegalArgumentException("referência fora do diretório de exportação");
      }
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao ler arquivo de exportação", e);
    }
  }

  public static String sha256(byte[] content) {
    return ExportStorage.sha256(content);
  }
}
