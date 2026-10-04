package br.gov.sus.nexus.core.platform.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

/**
 * Relay de DESENVOLVIMENTO do outbox: lê {@code platform.event_outbox} não publicado e emite no
 * canal correspondente ao {@code aggregate_type}, marcando {@code published_at}. Habilitado apenas
 * com {@code sus.outbox.relay.enabled=true}. Em produção o relay é feito pelo Debezium (Outbox
 * Event Router, CDC) e esta classe fica inativa.
 */
@ApplicationScoped
public class OutboxRelay {

  private static final Logger LOG = Logger.getLogger(OutboxRelay.class);

  /** aggregate_type → canal de saída (ver application.properties). */
  static final Map<String, String> CHANNELS =
      Map.ofEntries(
          Map.entry("citizen", "citizen-out"),
          Map.entry("merge_case", "merge-out"),
          Map.entry("appointment", "appointment-out"),
          Map.entry("care_task", "task-out"),
          Map.entry("integration_message", "integration-command-out"),
          Map.entry("regulation_request", "regulation-request-out"),
          Map.entry("regulation_status", "regulation-status-out"),
          Map.entry("exam_order", "exam-order-out"),
          Map.entry("exam_result", "exam-result-out"),
          Map.entry("hospital_episode", "hospital-adt-out"),
          Map.entry("hospital_discharge", "hospital-discharge-out"),
          Map.entry("care_plan", "careplan-out"),
          Map.entry("care_gap", "caregap-out"));

  @Inject EntityManager entityManager;
  @Inject ObjectMapper objectMapper;

  @Inject
  @Channel("citizen-out")
  MutinyEmitter<String> citizenOut;

  @Inject
  @Channel("merge-out")
  MutinyEmitter<String> mergeOut;

  @Inject
  @Channel("appointment-out")
  MutinyEmitter<String> appointmentOut;

  @Inject
  @Channel("task-out")
  MutinyEmitter<String> taskOut;

  @Inject
  @Channel("integration-command-out")
  MutinyEmitter<String> integrationCommandOut;

  @Inject
  @Channel("regulation-request-out")
  MutinyEmitter<String> regulationRequestOut;

  @Inject
  @Channel("regulation-status-out")
  MutinyEmitter<String> regulationStatusOut;

  @Inject
  @Channel("exam-order-out")
  MutinyEmitter<String> examOrderOut;

  @Inject
  @Channel("exam-result-out")
  MutinyEmitter<String> examResultOut;

  @Inject
  @Channel("hospital-adt-out")
  MutinyEmitter<String> hospitalAdtOut;

  @Inject
  @Channel("hospital-discharge-out")
  MutinyEmitter<String> hospitalDischargeOut;

  @Inject
  @Channel("careplan-out")
  MutinyEmitter<String> carePlanOut;

  @Inject
  @Channel("caregap-out")
  MutinyEmitter<String> careGapOut;

  @ConfigProperty(name = "sus.outbox.relay.enabled", defaultValue = "false")
  boolean enabled;

  @ConfigProperty(name = "sus.outbox.relay.batch-size", defaultValue = "100")
  int batchSize;

  record Row(
      String id,
      String tenantId,
      String aggregateType,
      String aggregateId,
      String eventType,
      String payload,
      String headers) {}

  @Scheduled(every = "{sus.outbox.relay.every}", identity = "outbox-relay")
  void scheduled() {
    if (!enabled) {
      return;
    }
    try {
      int n = relayOnce();
      if (n > 0) {
        LOG.debugf("outbox relay: %d evento(s) publicado(s)", n);
      }
    } catch (RuntimeException e) {
      LOG.warnf("outbox relay falhou: %s", e.getMessage());
    }
  }

  /** Publica um lote de eventos pendentes; retorna quantos foram publicados. */
  public int relayOnce() {
    List<Row> rows = pending();
    int published = 0;
    for (Row row : rows) {
      MutinyEmitter<String> emitter = emitterFor(row.aggregateType());
      if (emitter == null) {
        LOG.warnf(
            "outbox: aggregate_type sem canal mapeado: %s (%s)", row.aggregateType(), row.id());
        continue;
      }
      emitter.sendMessage(message(row)).await().atMost(Duration.ofSeconds(10));
      markPublished(row.id());
      published++;
    }
    return published;
  }

  private Message<String> message(Row row) {
    RecordHeaders headers = new RecordHeaders();
    try {
      JsonNode h = objectMapper.readTree(row.headers());
      h.fields()
          .forEachRemaining(
              e ->
                  headers.add(
                      e.getKey(),
                      (e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString())
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      LOG.debugf("outbox: headers ilegíveis em %s", row.id());
    }
    String key = partitionKey(row);
    return Message.of(row.payload())
        .addMetadata(
            OutgoingKafkaRecordMetadata.<String>builder()
                .withKey(key)
                .withHeaders(headers)
                .build());
  }

  /** Chave de partição: subject.municipal_citizen_id quando existir; senão o id do agregado. */
  private String partitionKey(Row row) {
    try {
      JsonNode p = objectMapper.readTree(row.payload());
      JsonNode subject = p.get("subject");
      if (subject != null && subject.hasNonNull("municipal_citizen_id")) {
        return subject.get("municipal_citizen_id").asText();
      }
    } catch (Exception e) {
      // cai no id do agregado
    }
    return row.aggregateId();
  }

  private MutinyEmitter<String> emitterFor(String aggregateType) {
    String channel = CHANNELS.get(aggregateType);
    if (channel == null) {
      return null;
    }
    return switch (channel) {
      case "citizen-out" -> citizenOut;
      case "merge-out" -> mergeOut;
      case "appointment-out" -> appointmentOut;
      case "task-out" -> taskOut;
      case "integration-command-out" -> integrationCommandOut;
      case "regulation-request-out" -> regulationRequestOut;
      case "regulation-status-out" -> regulationStatusOut;
      case "exam-order-out" -> examOrderOut;
      case "exam-result-out" -> examResultOut;
      case "hospital-adt-out" -> hospitalAdtOut;
      case "hospital-discharge-out" -> hospitalDischargeOut;
      case "careplan-out" -> carePlanOut;
      case "caregap-out" -> careGapOut;
      default -> null;
    };
  }

  private List<Row> pending() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              @SuppressWarnings("unchecked")
              List<Object[]> rows =
                  entityManager
                      .createNativeQuery(
                          "select id, tenant_id, aggregate_type, aggregate_id, event_type,"
                              + " payload::text, headers::text from platform.event_outbox"
                              + " where published_at is null order by created_at, id limit ?1")
                      .setParameter(1, batchSize)
                      .getResultList();
              return rows.stream()
                  .map(
                      r ->
                          new Row(
                              (String) r[0],
                              (String) r[1],
                              (String) r[2],
                              (String) r[3],
                              (String) r[4],
                              (String) r[5],
                              (String) r[6]))
                  .toList();
            });
  }

  private void markPublished(String id) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                entityManager
                    .createNativeQuery(
                        "update platform.event_outbox set published_at = now() where id = ?1")
                    .setParameter(1, id)
                    .executeUpdate());
  }
}
