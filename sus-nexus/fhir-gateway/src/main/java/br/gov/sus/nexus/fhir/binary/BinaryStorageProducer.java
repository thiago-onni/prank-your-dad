package br.gov.sus.nexus.fhir.binary;

import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import org.jboss.logging.Logger;

/** Seleciona a implementação de {@link BinaryStorage} pela configuração {@code sus.fhir.binary}. */
@ApplicationScoped
public class BinaryStorageProducer {

  private static final Logger LOG = Logger.getLogger(BinaryStorageProducer.class);

  @Inject FhirGatewayConfig config;

  @Produces
  @Singleton
  BinaryStorage storage() {
    FhirGatewayConfig.Binary b = config.binary();
    BinaryStorage storage =
        switch (b.storage()) {
          case "s3" ->
              S3BinaryStorage.fromConfig(
                  b.s3().bucket(),
                  b.s3().region(),
                  b.s3().endpoint(),
                  b.s3().pathStyle(),
                  b.s3().accessKey(),
                  b.s3().secretKey());
          case "file" -> new FileBinaryStorage(Path.of(b.fileDir()));
          default ->
              throw new IllegalStateException(
                  "sus.fhir.binary.storage inválido: " + b.storage() + " (file|s3)");
        };
    LOG.infof("Armazenamento de Binary: %s", storage.describe());
    return storage;
  }
}
