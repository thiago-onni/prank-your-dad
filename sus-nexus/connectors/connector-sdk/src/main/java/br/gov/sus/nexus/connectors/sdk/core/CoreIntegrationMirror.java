package br.gov.sus.nexus.connectors.sdk.core;

import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.ErrorDetails;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import br.gov.sus.nexus.connectors.sdk.api.ReconciliationReport;
import br.gov.sus.nexus.connectors.sdk.config.ConnectorConfig;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessage;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageStatus;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Espelha no core (tag {@code integration} do OpenAPI) o estado de cada {@code integration_message}
 * ({@code POST /api/v1/integration/messages}, sem payload), o heartbeat do conector e a
 * reconciliação. Habilitado por {@code connector.core.mirror.enabled}; com {@code
 * connector.core.mirror.pipeline-messages=true} o {@code ConnectorRuntime} espelha automaticamente
 * cada mensagem ao terminar (publicada ou DLQ) — pré-requisito para o core oferecer o
 * reprocessamento ({@code sus.integration.command.v1}).
 *
 * <p>Melhor esforço: falha no core é registrada (sem PII) e não desfaz o processamento local (o
 * ledger do conector continua sendo a fonte).
 */
@ApplicationScoped
public class CoreIntegrationMirror {

  private static final Logger LOG = Logger.getLogger(CoreIntegrationMirror.class);

  private final CoreIntegrationApi api;
  private final ConnectorConfig config;

  @Inject
  public CoreIntegrationMirror(@RestClient CoreIntegrationApi api, ConnectorConfig config) {
    this.api = api;
    this.config = config;
  }

  /** Espelhamento habilitado ({@code connector.core.mirror.enabled}). */
  public boolean enabled() {
    return config.core().mirror().enabled();
  }

  /** O runtime deve espelhar cada mensagem ao fim do pipeline. */
  public boolean pipelineMessages() {
    return enabled() && config.core().mirror().pipelineMessages();
  }

  public void message(IntegrationMessage m, String owner, String dlqReason) {
    if (!enabled() || m == null) return;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", m.id());
    body.put("connector_id", m.connectorId());
    body.put("source_system", m.sourceSystem());
    body.put("source_record_id", m.sourceRecordId());
    if (m.sourceRecordVersion() != null) body.put("source_record_version", m.sourceRecordVersion());
    body.put("entity_type", m.entityType());
    body.put("status", m.status().apiValue());
    if (m.rawRef() != null) body.put("raw_ref", m.rawRef());
    if (m.rawSha256() != null) body.put("raw_sha256", m.rawSha256());
    body.put("correlation_id", m.correlationId());
    body.put("received_at", m.receivedAt().toString());
    if (m.processedAt() != null) body.put("processed_at", m.processedAt().toString());
    body.put("attempts", m.attempts());
    ErrorDetails error = m.lastError();
    if (error != null) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("code", error.code());
      e.put("message", Pii.maskText(error.message()));
      e.put("stage", error.stage());
      if (error.occurredAt() != null) e.put("occurred_at", error.occurredAt().toString());
      body.put("last_error", e);
    }
    if (m.status() == IntegrationMessageStatus.DEAD_LETTERED) {
      Map<String, Object> dl = new LinkedHashMap<>();
      dl.put("topic", "sus.dlq.v1");
      dl.put("reason", Pii.maskText(dlqReason == null ? "desconhecido" : dlqReason));
      dl.put("stage", error == null ? "unknown" : error.stage());
      dl.put("owner", owner);
      if (m.rawRef() != null) dl.put("payload_ref", m.rawRef());
      body.put("dead_letter", dl);
    }
    call("ledger " + m.id(), () -> api.recordIntegrationMessage(m.correlationId(), body));
  }

  public void heartbeat(ConnectorDescriptor d, HealthStatus health, Map<String, Object> metrics) {
    if (!enabled()) return;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("connector_version", d.connectorVersion());
    body.put("source_system", d.sourceSystem());
    body.put("health", health.state().name().toLowerCase());
    if (!health.details().isEmpty()) body.put("detail", health.details().toString());
    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("connector_id", d.connectorId());
    descriptor.put("supported_entities", d.supportedEntities());
    descriptor.put("supported_protocols", d.supportedProtocols());
    descriptor.put("authentication_method", d.authenticationMethod().name());
    descriptor.put("data_classification", d.dataClassification().name());
    descriptor.put("field_mapping_version", d.fieldMappingVersion());
    descriptor.put("owner", d.owner());
    body.put("descriptor", descriptor);
    body.put("metrics", metrics);
    call("heartbeat", () -> api.heartbeat(d.connectorId(), body));
  }

  public void reconciliation(ReconciliationReport report) {
    if (!enabled()) return;
    for (ReconciliationReport.Entry e : report.entries()) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("connector_id", report.connectorId());
      body.put("entity_type", e.entityType());
      body.put("period_start", report.period().start().toString());
      body.put("period_end", report.period().end().toString());
      body.put("source_count", e.sourceCount());
      body.put("bus_count", e.busCount());
      body.put("gap", e.gap());
      body.put("checked_at", report.checkedAt().toString());
      call("reconciliação " + e.entityType(), () -> api.recordReconciliation(body));
    }
  }

  private void call(String what, java.util.function.Supplier<Response> request) {
    try (Response r = request.get()) {
      if (r.getStatus() / 100 != 2) {
        LOG.warnf("core respondeu %d ao registrar %s", r.getStatus(), what);
      }
    } catch (RuntimeException e) {
      LOG.warnf(
          "falha ao registrar %s no core: %s", what, Pii.maskText(String.valueOf(e.getMessage())));
    }
  }
}
