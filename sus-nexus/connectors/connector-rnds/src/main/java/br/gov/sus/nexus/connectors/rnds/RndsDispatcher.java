package br.gov.sus.nexus.connectors.rnds;

import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmission;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStatus;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStore;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.runtime.ConnectorRuntime;
import br.gov.sus.nexus.connectors.sdk.runtime.PipelineHeaders;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;

/**
 * Gatilho → pipeline. Para cada evento do barramento: verifica aplicabilidade (ação/status), se o
 * modelo está habilitado e a idempotência por {@code event_id} ({@code rnds_submission}); então
 * entrega o envelope ao pipeline do SDK (síncrono: raw zone → transform → validate → publish →
 * retry/DLQ) e, ao final, espelha o ledger no core e fecha o estado da submissão.
 */
@ApplicationScoped
public class RndsDispatcher {

  /** Resultado do tratamento de um evento. */
  public enum Outcome {
    ACCEPTED,
    DEAD_LETTERED,
    IGNORED_NOT_APPLICABLE,
    IGNORED_DISABLED,
    DUPLICATE,
    INVALID_EVENT
  }

  private static final Logger LOG = Logger.getLogger(RndsDispatcher.class);
  private static final Set<String> FINAL_RESULT_STATUS = Set.of("final", "amended");

  private final RndsConnector connector;
  private final RndsSubmissionStore submissions;
  private final IntegrationMessageLedger ledger;
  private final CoreMirror core;
  private final RndsMetrics metrics;
  private final ProducerTemplate producer;
  private final ObjectMapper mapper;

  @Inject
  public RndsDispatcher(
      RndsConnector connector,
      RndsSubmissionStore submissions,
      IntegrationMessageLedger ledger,
      CoreMirror core,
      RndsMetrics metrics,
      ProducerTemplate producer,
      ObjectMapper mapper) {
    this.connector = connector;
    this.submissions = submissions;
    this.ledger = ledger;
    this.core = core;
    this.metrics = metrics;
    this.producer = producer;
    this.mapper = mapper;
  }

  /** Modelo candidato por tópico de origem. */
  public Outcome handle(String model, String envelopeJson) {
    JsonNode envelope;
    try {
      envelope = mapper.readTree(envelopeJson);
    } catch (IOException e) {
      LOG.warnf("evento ilegível descartado (%s)", model);
      metrics.ignored(model, "invalid_event");
      return Outcome.INVALID_EVENT;
    }
    String eventId = envelope.path("event_id").asText(null);
    JsonNode data = envelope.path("data");
    if (eventId == null || eventId.isBlank() || !data.isObject()) {
      LOG.warnf("evento sem event_id/data descartado (%s)", model);
      metrics.ignored(model, "invalid_event");
      return Outcome.INVALID_EVENT;
    }
    if (!applicable(model, data)) {
      LOG.debugf("evento %s não se aplica ao modelo %s", eventId, model);
      metrics.ignored(model, "not_applicable");
      return Outcome.IGNORED_NOT_APPLICABLE;
    }
    if (!connector.enabled(model)) {
      LOG.debugf("modelo %s desabilitado: evento %s ignorado", model, eventId);
      metrics.ignored(model, "disabled");
      return Outcome.IGNORED_DISABLED;
    }
    String sourceId;
    try {
      sourceId = RndsConnector.stableId(model, RndsConnector.eventData(data));
    } catch (RuntimeException e) {
      metrics.ignored(model, "invalid_event");
      return Outcome.INVALID_EVENT;
    }
    if (!claim(eventId, model, sourceId)) {
      LOG.infof("evento %s já processado (idempotência por event_id)", eventId);
      metrics.ignored(model, "duplicate");
      return Outcome.DUPLICATE;
    }
    return runPipeline(eventId, model, envelopeJson, envelope);
  }

  private boolean applicable(String model, JsonNode data) {
    String action = data.path("action").asText("");
    return switch (model) {
      case BundleAssembler.RESULTADO_EXAME ->
          "available".equals(action)
              && FINAL_RESULT_STATUS.contains(data.path("result_status").asText(""));
      case BundleAssembler.SUMARIO_ALTA -> "completed".equals(action);
      default -> false;
    };
  }

  /** Idempotência: novo → claim; FAILED (DLQ reprocessável) → reabre; demais → duplicado. */
  private boolean claim(String eventId, String model, String sourceId) {
    Optional<RndsSubmission> existing = submissions.findByEventId(eventId);
    if (existing.isEmpty()) {
      return submissions.claim(RndsSubmission.pending(eventId, model, sourceId));
    }
    if (existing.get().status() == RndsSubmissionStatus.FAILED) {
      submissions.save(existing.get().withStatus(RndsSubmissionStatus.PENDING, "reprocessamento"));
      return true;
    }
    return false;
  }

  private Outcome runPipeline(String eventId, String model, String json, JsonNode envelope) {
    String correlation = envelope.path("trace").path("correlation_id").asText(null);
    if (correlation == null || correlation.isBlank()) correlation = Ids.correlation();
    RawMessage raw =
        new RawMessage(
            eventId,
            null,
            RndsConnector.entityType(model),
            "application/json",
            json.getBytes(StandardCharsets.UTF_8),
            Map.of(
                RndsConnector.META_MODEL,
                model,
                "event_type",
                envelope.path("event_type").asText("")),
            Instant.now());
    String correlationId = correlation;
    Exchange result =
        producer.send(
            ConnectorRuntime.INGEST,
            ex -> {
              ex.getIn().setBody(raw);
              ex.getIn().setHeader(PipelineHeaders.CORRELATION_ID, correlationId);
            });
    String messageId = result.getIn().getHeader(PipelineHeaders.MESSAGE_ID, String.class);
    Optional<IntegrationMessage> message =
        messageId == null ? Optional.empty() : ledger.findById(messageId);
    boolean dead =
        message.map(m -> m.status() == IntegrationMessageStatus.DEAD_LETTERED).orElse(true);
    RndsSubmission submission =
        submissions
            .findByEventId(eventId)
            .orElse(RndsSubmission.pending(eventId, model, null))
            .withMessage(messageId);
    String reason =
        message
            .map(IntegrationMessage::lastError)
            .map(e -> Pii.maskText(e.message()))
            .orElse("falha antes do registro no ledger");
    if (dead
        && (submission.status() == RndsSubmissionStatus.PENDING
            || submission.status() == RndsSubmissionStatus.RETRYING)) {
      submission = submission.withStatus(RndsSubmissionStatus.FAILED, reason);
      metrics.submission(model, "failed");
    }
    submissions.save(submission);
    message.ifPresent(m -> core.message(m, connector.descriptor().owner(), reason));
    return dead ? Outcome.DEAD_LETTERED : Outcome.ACCEPTED;
  }
}
