package br.gov.sus.nexus.connectors.rnds.submission;

import br.gov.sus.nexus.connectors.rnds.RndsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import javax.sql.DataSource;

/** Seleciona o store de {@code rnds_submission} por {@code rnds.submission-store.type}. */
@ApplicationScoped
public class SubmissionStoreProducer {

  @Produces
  @Singleton
  public RndsSubmissionStore store(RndsConfig config, Instance<DataSource> dataSource) {
    if ("jdbc".equalsIgnoreCase(config.submissionStore().type())) {
      if (dataSource.isUnsatisfied()) {
        throw new IllegalStateException(
            "rnds.submission-store.type=jdbc requer um DataSource (quarkus.datasource.*)");
      }
      JdbcRndsSubmissionStore store = new JdbcRndsSubmissionStore(dataSource.get());
      if (config.submissionStore().createSchema()) store.createSchemaIfMissing();
      return store;
    }
    return new InMemoryRndsSubmissionStore();
  }
}
