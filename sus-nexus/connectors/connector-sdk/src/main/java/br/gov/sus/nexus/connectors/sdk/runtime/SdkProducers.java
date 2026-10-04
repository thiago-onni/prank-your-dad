package br.gov.sus.nexus.connectors.sdk.runtime;

import br.gov.sus.nexus.connectors.sdk.config.ConnectorConfig;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.JdbcIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.raw.FileSystemRawMessageStore;
import br.gov.sus.nexus.connectors.sdk.raw.RawMessageStore;
import br.gov.sus.nexus.connectors.sdk.raw.S3RawMessageStore;
import br.gov.sus.nexus.connectors.sdk.retry.DeadLetterSink;
import br.gov.sus.nexus.connectors.sdk.retry.FileDeadLetterSink;
import br.gov.sus.nexus.connectors.sdk.retry.LogDeadLetterSink;
import br.gov.sus.nexus.connectors.sdk.retry.RetryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.net.URI;
import java.nio.file.Path;
import javax.sql.DataSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Beans padrão do SDK selecionados por configuração. Um conector pode sobrescrever qualquer um
 * declarando o próprio bean (os daqui são {@link DefaultBean}).
 */
@ApplicationScoped
public class SdkProducers {

  @Produces
  @Singleton
  @DefaultBean
  public RawMessageStore rawMessageStore(ConnectorConfig config, ObjectMapper mapper) {
    ConnectorConfig.RawStore cfg = config.rawStore();
    if ("s3".equalsIgnoreCase(cfg.type())) {
      var builder =
          S3Client.builder()
              .region(Region.of(cfg.region()))
              .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
      cfg.endpoint().ifPresent(e -> builder.endpointOverride(URI.create(e)));
      String bucket =
          cfg.bucket()
              .orElseThrow(
                  () ->
                      new IllegalStateException("connector.raw-store.bucket obrigatório para s3"));
      return new S3RawMessageStore(builder.build(), bucket, cfg.prefix());
    }
    return new FileSystemRawMessageStore(Path.of(cfg.dir()), mapper);
  }

  @Produces
  @Singleton
  @DefaultBean
  public IntegrationMessageLedger ledger(ConnectorConfig config, Instance<DataSource> dataSource) {
    if ("jdbc".equalsIgnoreCase(config.ledger().type())) {
      if (dataSource.isUnsatisfied()) {
        throw new IllegalStateException(
            "connector.ledger.type=jdbc requer um DataSource (quarkus-agroal)");
      }
      JdbcIntegrationMessageLedger ledger = new JdbcIntegrationMessageLedger(dataSource.get());
      if (config.ledger().createSchema()) ledger.createSchemaIfMissing();
      return ledger;
    }
    return new InMemoryIntegrationMessageLedger();
  }

  @Produces
  @Singleton
  @DefaultBean
  public DeadLetterSink deadLetterSink(ConnectorConfig config, ObjectMapper mapper) {
    if ("log".equalsIgnoreCase(config.dlq().type())) return new LogDeadLetterSink();
    return new FileDeadLetterSink(Path.of(config.dlq().dir()), mapper);
  }

  @Produces
  @Singleton
  @DefaultBean
  public RetryPolicy retryPolicy(ConnectorConfig config) {
    return RetryPolicy.from(config.retry());
  }
}
