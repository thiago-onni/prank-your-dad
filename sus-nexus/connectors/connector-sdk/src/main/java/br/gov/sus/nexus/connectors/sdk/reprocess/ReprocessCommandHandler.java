package br.gov.sus.nexus.connectors.sdk.reprocess;

import br.gov.sus.nexus.connectors.sdk.api.Connector;
import br.gov.sus.nexus.connectors.sdk.config.ConnectorConfig;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Tratamento genérico do comando {@code sus.integration.reprocess.requested} (tópico {@code
 * sus.integration.command.v1}, KAF-012), independente do transporte: o conector que consome Kafka
 * liga um {@code @Incoming} de uma linha a {@link #handle(String)} (o SDK não depende de Kafka).
 *
 * <p>Filtros, nesta ordem: envelope legível e no contrato ({@code message_id}, {@code
 * suppress_external_effects=true}); {@code event_type}; {@code tenant} = {@code
 * connector.tenant-id}; {@code connector_id} = o deste conector (o tópico é compartilhado, chave
 * {@code data.connector_id}); comando ainda não tratado ({@code event_id}). Depois delega ao {@link
 * Reprocessor} (padrão {@link PipelineReprocessor}). Métrica {@value #METRIC}{@code {connector_id,
 * result}}. Logs só com ids (sem PII).
 */
@ApplicationScoped
public class ReprocessCommandHandler {

  public static final String METRIC = "connector_reprocess_total";
  private static final Logger LOG = Logger.getLogger(ReprocessCommandHandler.class);
  private static final int SEEN_CAPACITY = 10_000;

  private final Connector connector;
  private final ConnectorConfig config;
  private final Reprocessor reprocessor;
  private final ConnectorMetrics metrics;
  private final ObjectMapper mapper;
  private final Map<String, Boolean> seen =
      new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
          return size() > SEEN_CAPACITY;
        }
      };

  @Inject
  public ReprocessCommandHandler(
      Connector connector,
      ConnectorConfig config,
      Reprocessor reprocessor,
      ConnectorMetrics metrics,
      ObjectMapper mapper) {
    this.connector = connector;
    this.config = config;
    this.reprocessor = reprocessor;
    this.metrics = metrics;
    this.mapper = mapper;
  }

  /** Trata um envelope JSON recebido do barramento. Nunca lança exceção. */
  public ReprocessResult handle(String envelopeJson) {
    Optional<ReprocessCommand> parsed = ReprocessCommand.parse(mapper, envelopeJson);
    if (parsed.isEmpty()) {
      LOG.warn("comando de reprocessamento ilegível descartado");
      return record(ReprocessResult.of(ReprocessResult.Status.INVALID, null, "envelope ilegível"));
    }
    ReprocessCommand cmd = parsed.get();
    String connectorId = connector.descriptor().connectorId();
    if (!ReprocessCommand.EVENT_TYPE.equals(cmd.eventType())) {
      return record(
          ReprocessResult.of(
              ReprocessResult.Status.IGNORED, cmd.messageId(), "event_type " + cmd.eventType()));
    }
    if (cmd.connectorId() != null && !connectorId.equals(cmd.connectorId())) {
      LOG.debugf("comando %s é para %s; ignorado", cmd.eventId(), cmd.connectorId());
      return record(
          ReprocessResult.of(ReprocessResult.Status.IGNORED, cmd.messageId(), "outro conector"));
    }
    if (cmd.tenantId() == null || !cmd.tenantId().equals(config.tenantId())) {
      LOG.warnf("comando %s de outro tenant ignorado", cmd.eventId());
      return record(
          ReprocessResult.of(ReprocessResult.Status.IGNORED, cmd.messageId(), "outro tenant"));
    }
    if (cmd.connectorId() == null
        || cmd.messageId() == null
        || !cmd.messageId().startsWith("msg_")
        || !cmd.suppressExternalEffects()) {
      LOG.warnf("comando %s fora do contrato reprocess.v1; descartado", cmd.eventId());
      return record(
          ReprocessResult.of(
              ReprocessResult.Status.INVALID, cmd.messageId(), "fora do contrato reprocess.v1"));
    }
    synchronized (seen) {
      if (seen.containsKey(cmd.eventId())) {
        return record(
            ReprocessResult.of(
                ReprocessResult.Status.DUPLICATE, cmd.messageId(), "comando já tratado"));
      }
    }
    ReprocessResult result;
    try {
      result = reprocessor.reprocess(cmd);
    } catch (RuntimeException e) {
      LOG.errorf("falha ao reprocessar %s: %s", cmd.messageId(), Pii.maskText(String.valueOf(e)));
      return record(
          ReprocessResult.of(
              ReprocessResult.Status.ERROR,
              cmd.messageId(),
              Pii.maskText(e.getClass().getSimpleName())));
    }
    synchronized (seen) {
      seen.put(cmd.eventId(), Boolean.TRUE);
    }
    LOG.infof(
        "reprocessamento de %s solicitado por %s: %s (%s)",
        cmd.messageId(),
        cmd.requestedBy(),
        result.status().tag(),
        Pii.maskText(String.valueOf(result.detail())));
    return record(result);
  }

  private ReprocessResult record(ReprocessResult result) {
    metrics.counter(
        METRIC,
        1,
        "connector_id",
        connector.descriptor().connectorId(),
        "result",
        result.status().tag());
    return result;
  }

  /** Esquece comandos já tratados (testes). */
  public void reset() {
    synchronized (seen) {
      seen.clear();
    }
  }
}
