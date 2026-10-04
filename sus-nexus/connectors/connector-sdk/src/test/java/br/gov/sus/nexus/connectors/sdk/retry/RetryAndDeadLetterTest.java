package br.gov.sus.nexus.connectors.sdk.retry;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sus.nexus.connectors.sdk.api.FailedMessage;
import br.gov.sus.nexus.connectors.sdk.api.RetryDecision;
import br.gov.sus.nexus.connectors.sdk.ledger.InMemoryIntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetryAndDeadLetterTest {

  private final RetryPolicy policy =
      new RetryPolicy(3, Duration.ofMillis(100), 2.0, Duration.ofMillis(350));

  @Test
  void backoffExponencialLimitado() {
    assertThat(policy.delayFor(1)).isEqualTo(Duration.ofMillis(100));
    assertThat(policy.delayFor(2)).isEqualTo(Duration.ofMillis(200));
    assertThat(policy.delayFor(3)).isEqualTo(Duration.ofMillis(350));
    assertThat(policy.delayFor(10)).isEqualTo(Duration.ofMillis(350));
  }

  @Test
  void decideRetryAteEsgotarEDlqParaPermanente() {
    RuntimeException err = new RuntimeException("503");
    RetryDecision d1 = policy.decide(new FailedMessage("msg_1", "publish", 1, err, false));
    assertThat(d1.retry()).isTrue();
    assertThat(d1.delay()).isEqualTo(Duration.ofMillis(100));
    RetryDecision d2 = policy.decide(new FailedMessage("msg_1", "publish", 2, err, false));
    assertThat(d2.delay()).isEqualTo(Duration.ofMillis(200));
    assertThat(policy.decide(new FailedMessage("msg_1", "publish", 3, err, false)).retry())
        .isFalse();
    assertThat(policy.decide(new FailedMessage("msg_1", "validate", 1, err, true)).retry())
        .isFalse();
  }

  @Test
  void handlerGravaDlqEmArquivoEAtualizaLedger(@TempDir Path dir) {
    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    FileDeadLetterSink sink = new FileDeadLetterSink(dir, mapper);
    InMemoryIntegrationMessageLedger ledger = new InMemoryIntegrationMessageLedger();
    ConnectorMetrics metrics = new ConnectorMetrics(new SimpleMeterRegistry());
    DeadLetterHandler handler = new DeadLetterHandler(sink, ledger, metrics);

    IntegrationMessage m =
        ledger.save(
            IntegrationMessage.received(
                "connector-test", "TESTE", "r1", null, "citizen", "file:///raw/r1", "sha", null));
    DeadLetter dl =
        handler.handle(
            m,
            new FailedMessage(
                m.id(), "publish", 3, new RuntimeException("core 400 cpf 123.456.789-09"), true),
            "equipe-integracao");

    assertThat(dl.id()).startsWith("dlq_");
    assertThat(dl.reason()).contains("***.***.***-09").doesNotContain("123.456");
    assertThat(dl.payloadRef()).isEqualTo("file:///raw/r1");
    assertThat(m.status()).isEqualTo(IntegrationMessageStatus.DEAD_LETTERED);
    assertThat(sink.open(10))
        .hasSize(1)
        .first()
        .extracting(DeadLetter::messageId)
        .isEqualTo(m.id());
    assertThat(
            metrics.counterValue(
                ConnectorMetrics.FAILED, "connector_id", "connector-test", "stage", "publish"))
        .isEqualTo(1.0);
  }
}
