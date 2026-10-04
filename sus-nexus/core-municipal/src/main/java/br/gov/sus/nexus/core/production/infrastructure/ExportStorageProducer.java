package br.gov.sus.nexus.core.production.infrastructure;

import br.gov.sus.nexus.core.production.application.ExportStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Seleciona o {@link ExportStorage} por {@code sus.production.export-storage}: {@code file}
 * (padrão; {@code sus.production.export-dir}) ou {@code s3} ({@code sus.production.s3.*}: bucket —
 * padrão {@code production-exports} —, região, endpoint MinIO, path-style, credenciais, prefixo,
 * SSE {@code AES256|aws:kms|none} e chave KMS). Valor desconhecido ⇒ falha no start.
 */
@ApplicationScoped
public class ExportStorageProducer {

  private static final Logger LOG = Logger.getLogger(ExportStorageProducer.class);

  @ConfigProperty(name = "sus.production.export-storage", defaultValue = "file")
  String kind;

  @ConfigProperty(name = "sus.production.export-dir")
  String exportDir;

  @ConfigProperty(name = "sus.production.s3.bucket", defaultValue = "production-exports")
  String bucket;

  @ConfigProperty(name = "sus.production.s3.region", defaultValue = "us-east-1")
  String region;

  @ConfigProperty(name = "sus.production.s3.endpoint")
  Optional<String> endpoint;

  @ConfigProperty(name = "sus.production.s3.path-style", defaultValue = "true")
  boolean pathStyle;

  @ConfigProperty(name = "sus.production.s3.access-key")
  Optional<String> accessKey;

  @ConfigProperty(name = "sus.production.s3.secret-key")
  Optional<String> secretKey;

  @ConfigProperty(name = "sus.production.s3.prefix")
  Optional<String> prefix;

  @ConfigProperty(name = "sus.production.s3.sse", defaultValue = "AES256")
  String sse;

  @ConfigProperty(name = "sus.production.s3.kms-key-id")
  Optional<String> kmsKeyId;

  @Produces
  @ApplicationScoped
  ExportStorage exportStorage() {
    return create();
  }

  ExportStorage create() {
    String k = kind == null ? "file" : kind.trim().toLowerCase();
    return switch (k) {
      case "file" -> {
        LOG.infof("exportação de produção em arquivo (%s)", exportDir);
        yield new FileExportStorage(exportDir);
      }
      case "s3" -> {
        LOG.infof("exportação de produção em object storage s3://%s (sse=%s)", bucket, sse);
        yield S3ExportStorage.fromConfig(
            bucket,
            region,
            endpoint,
            pathStyle,
            accessKey,
            secretKey,
            prefix.orElse(""),
            sse,
            kmsKeyId);
      }
      default ->
          throw new IllegalStateException(
              "sus.production.export-storage inválido (file | s3): " + kind);
    };
  }

  void close(@Disposes ExportStorage storage) {
    if (storage instanceof AutoCloseable c) {
      try {
        c.close();
      } catch (Exception e) {
        LOG.debugf("falha ao fechar ExportStorage: %s", e.getClass().getSimpleName());
      }
    }
  }
}
