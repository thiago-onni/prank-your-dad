package br.gov.sus.nexus.core.production.infrastructure;

import br.gov.sus.nexus.core.production.application.ExportStorage;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link ExportStorage} em sistema de arquivos ({@code sus.production.export-dir}). O arquivo nunca
 * é sobrescrito (CREATE_NEW): reexportar gera novo nome. Referência {@code file://...}.
 */
@ApplicationScoped
public class FileExportStorage implements ExportStorage {

  @ConfigProperty(name = "sus.production.export-dir")
  String exportDir;

  @Override
  public StoredFile store(String tenantId, String name, byte[] content) {
    if (!tenantId.matches("^ibge_[0-9]{7}$") || !name.matches("^[A-Za-z0-9_.-]+$")) {
      throw new IllegalArgumentException("nome de arquivo de exportação inválido");
    }
    try {
      Path dir = Path.of(exportDir).toAbsolutePath().normalize().resolve(tenantId);
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
      if (!file.startsWith(Path.of(exportDir).toAbsolutePath().normalize())) {
        throw new IllegalArgumentException("referência fora do diretório de exportação");
      }
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException("falha ao ler arquivo de exportação", e);
    }
  }

  public static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
