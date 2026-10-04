package br.gov.sus.nexus.connectors.rnds;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.rnds.submission.JdbcRndsSubmissionStore;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmission;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStatus;
import br.gov.sus.nexus.connectors.sdk.api.Period;
import java.time.Duration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** {@code rnds_submission} em JDBC (H2 em memória, modo PostgreSQL). */
class JdbcRndsSubmissionStoreTest {

  @Test
  void claimIdempotenteUpsertEListagem() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:rnds;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    JdbcRndsSubmissionStore store = new JdbcRndsSubmissionStore(ds);
    store.createSchemaIfMissing();
    store.createSchemaIfMissing(); // idempotente

    RndsSubmission pending = RndsSubmission.pending("evt_1", "resultado-exame", Fixtures.EXR);
    assertThat(store.claim(pending)).isTrue();
    assertThat(store.claim(pending)).isFalse();

    RndsSubmission accepted =
        pending
            .withMessage("msg_1")
            .attempt("a".repeat(64))
            .withResponse(RndsSubmissionStatus.ACCEPTED, 201, "loc-1", "aceito HTTP 201", null);
    store.save(accepted);

    RndsSubmission read = store.findByEventId("evt_1").orElseThrow();
    assertThat(read.status()).isEqualTo(RndsSubmissionStatus.ACCEPTED);
    assertThat(read.httpStatus()).isEqualTo(201);
    assertThat(read.protocol()).isEqualTo("loc-1");
    assertThat(read.attempts()).isEqualTo(1);
    assertThat(read.integrationMessageId()).isEqualTo("msg_1");
    assertThat(store.list("resultado-exame", Period.last(Duration.ofMinutes(5)))).hasSize(1);
    assertThat(store.list("sumario-alta", null)).isEmpty();
    store.clear();
    assertThat(store.findByEventId("evt_1")).isEmpty();
  }
}
