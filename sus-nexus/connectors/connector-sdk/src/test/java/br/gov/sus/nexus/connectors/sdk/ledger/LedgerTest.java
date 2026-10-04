package br.gov.sus.nexus.connectors.sdk.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sus.nexus.connectors.sdk.api.Period;
import java.time.Duration;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class LedgerTest {

  private static IntegrationMessage sample(String entity) {
    return IntegrationMessage.received(
        "connector-test", "TESTE", "rec-1", "v1", entity, "file:///raw/x", "abc", null);
  }

  @Test
  void idEhUlidPrefixadoETransicoesValidas() {
    IntegrationMessage m = sample("citizen");
    assertThat(m.id()).matches("^msg_[0-9A-HJKMNP-TV-Z]{26}$");
    assertThat(m.correlationId()).startsWith("corr_");
    m.transitionTo(IntegrationMessageStatus.TRANSFORMED);
    m.transitionTo(IntegrationMessageStatus.VALIDATED);
    m.transitionTo(IntegrationMessageStatus.PUBLISHED);
    assertThat(m.processedAt()).isNotNull();
    m.transitionTo(IntegrationMessageStatus.PROCESSED);
    assertThatThrownBy(() -> m.transitionTo(IntegrationMessageStatus.RECEIVED))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void falhaIncrementaTentativasERegistraErro() {
    IntegrationMessage m = sample("citizen");
    m.fail("publish", "CoreClientException", "core respondeu 503");
    assertThat(m.status()).isEqualTo(IntegrationMessageStatus.FAILED);
    assertThat(m.attempts()).isEqualTo(1);
    assertThat(m.lastError().stage()).isEqualTo("publish");
    m.transitionTo(IntegrationMessageStatus.REPROCESSING);
    m.transitionTo(IntegrationMessageStatus.PUBLISHED);
  }

  @Test
  void ledgerEmMemoriaContaPorEntidadeEPeriodo() {
    InMemoryIntegrationMessageLedger ledger = new InMemoryIntegrationMessageLedger();
    IntegrationMessage a = ledger.save(sample("citizen"));
    IntegrationMessage b = ledger.save(sample("appointment"));
    a.transitionTo(IntegrationMessageStatus.TRANSFORMED);
    a.transitionTo(IntegrationMessageStatus.VALIDATED);
    a.transitionTo(IntegrationMessageStatus.PUBLISHED);
    Period period = Period.last(Duration.ofMinutes(1));
    assertThat(ledger.countPublished("citizen", period)).isEqualTo(1);
    assertThat(ledger.countPublished("appointment", period)).isZero();
    assertThat(ledger.findById(b.id())).isPresent();
    assertThat(ledger.findByStatus(IntegrationMessageStatus.RECEIVED, 10)).containsExactly(b);
  }

  @Test
  void ledgerJdbcPersisteEConsulta() {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:ledger;DB_CLOSE_DELAY=-1");
    JdbcIntegrationMessageLedger ledger = new JdbcIntegrationMessageLedger(ds);
    ledger.createSchemaIfMissing();

    IntegrationMessage m = sample("citizen");
    ledger.save(m);
    m.transitionTo(IntegrationMessageStatus.TRANSFORMED);
    m.fail("validate", "ValidationException", "lote inválido");
    ledger.save(m);

    IntegrationMessage loaded = ledger.findById(m.id()).orElseThrow();
    assertThat(loaded.status()).isEqualTo(IntegrationMessageStatus.FAILED);
    assertThat(loaded.attempts()).isEqualTo(1);
    assertThat(loaded.lastError().code()).isEqualTo("ValidationException");
    assertThat(loaded.rawSha256()).isEqualTo("abc");
    assertThat(ledger.findByStatus(IntegrationMessageStatus.FAILED, 5))
        .extracting(IntegrationMessage::id)
        .isEqualTo(List.of(m.id()));
    assertThat(
            ledger.count(
                "citizen", IntegrationMessageStatus.FAILED, Period.last(Duration.ofMinutes(1))))
        .isEqualTo(1);
    assertThat(ledger.countPublished("citizen", null)).isZero();
  }
}
