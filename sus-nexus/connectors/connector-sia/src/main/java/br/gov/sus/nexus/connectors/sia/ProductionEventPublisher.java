package br.gov.sus.nexus.connectors.sia;

import br.gov.sus.nexus.connectors.sdk.api.ConnectorException;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;

/**
 * Publica envelopes em {@code sus.ingest.production.v1} (canal {@value #CHANNEL}) de forma
 * síncrona: espera o ack do broker ({@code sia.publish.ack-timeout}); nack/timeout viram erro
 * <b>transitório</b> do pipeline (retry exponencial → DLQ). Chave = {@code source.source_record_id}
 * (catálogo de tópicos); cabeçalhos {@code ce_*}, {@code tenant_id}, {@code correlation_id}, {@code
 * schema_version}, {@code replay}. Nunca loga o payload.
 */
@ApplicationScoped
public class ProductionEventPublisher {

  public static final String CHANNEL = "ingest-production-out";

  private final Emitter<String> emitter;
  private final SiaConfig config;

  @Inject
  public ProductionEventPublisher(@Channel(CHANNEL) Emitter<String> emitter, SiaConfig config) {
    this.emitter = emitter;
    this.config = config;
  }

  public void publish(String key, String envelopeJson, Map<String, String> headers) {
    RecordHeaders kafkaHeaders = new RecordHeaders();
    headers.forEach((k, v) -> kafkaHeaders.add(k, v.getBytes(StandardCharsets.UTF_8)));
    CompletableFuture<Void> acked = new CompletableFuture<>();
    Message<String> message =
        Message.of(envelopeJson)
            .addMetadata(
                OutgoingKafkaRecordMetadata.<String>builder()
                    .withKey(key)
                    .withHeaders(kafkaHeaders)
                    .build())
            .withAck(
                () -> {
                  acked.complete(null);
                  return CompletableFuture.completedFuture(null);
                })
            .withNack(
                t -> {
                  acked.completeExceptionally(t);
                  return CompletableFuture.completedFuture(null);
                });
    try {
      emitter.send(message);
      Duration timeout = config.publish().ackTimeout();
      acked.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.transientError("publish", "publicação interrompida", e);
    } catch (TimeoutException e) {
      throw ConnectorException.transientError("publish", "sem ack do Kafka no prazo", e);
    } catch (ExecutionException e) {
      throw ConnectorException.transientError(
          "publish",
          "Kafka recusou a publicação: " + e.getCause().getClass().getSimpleName(),
          e.getCause());
    } catch (RuntimeException e) {
      // emitter sem demanda/buffer cheio ou canal indisponível
      throw ConnectorException.transientError(
          "publish", "canal Kafka indisponível: " + e.getClass().getSimpleName(), e);
    }
  }
}
