package br.gov.sus.nexus.connectors.sdk.runtime;

import br.gov.sus.nexus.connectors.sdk.api.AuthResult;
import br.gov.sus.nexus.connectors.sdk.api.CanonicalBatch;
import br.gov.sus.nexus.connectors.sdk.api.Capabilities;
import br.gov.sus.nexus.connectors.sdk.api.Connector;
import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.ErrorDetails;
import br.gov.sus.nexus.connectors.sdk.api.FailedMessage;
import br.gov.sus.nexus.connectors.sdk.api.HealthStatus;
import br.gov.sus.nexus.connectors.sdk.api.IngestRequest;
import br.gov.sus.nexus.connectors.sdk.api.IngestResult;
import br.gov.sus.nexus.connectors.sdk.api.MetricsSink;
import br.gov.sus.nexus.connectors.sdk.api.Period;
import br.gov.sus.nexus.connectors.sdk.api.PublishResult;
import br.gov.sus.nexus.connectors.sdk.api.RawMessage;
import br.gov.sus.nexus.connectors.sdk.api.ReconciliationReport;
import br.gov.sus.nexus.connectors.sdk.api.RetryDecision;
import br.gov.sus.nexus.connectors.sdk.core.CorePublisher;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import br.gov.sus.nexus.connectors.sdk.reconcile.ReconciliationJob;
import br.gov.sus.nexus.connectors.sdk.reconcile.SourceCounter;
import br.gov.sus.nexus.connectors.sdk.retry.RetryPolicy;
import br.gov.sus.nexus.connectors.sdk.util.Ids;
import jakarta.inject.Inject;
import java.util.List;
import org.apache.camel.ProducerTemplate;

/**
 * Base conveniente: publica via {@link CorePublisher}, retry via {@link RetryPolicy}, erros via
 * ledger, métricas via {@link ConnectorMetrics} e reconciliação via {@link ReconciliationJob}.
 * Subclasses implementam {@link #descriptor()}, {@link #transform(RawMessage)}, {@link
 * #validate(CanonicalBatch)} e, normalmente, {@link #sourceCounter()}.
 */
public abstract class AbstractConnector implements Connector {

  @Inject protected CorePublisher corePublisher;
  @Inject protected IntegrationMessageLedger ledger;
  @Inject protected ConnectorMetrics metrics;
  @Inject protected RetryPolicy retryPolicy;
  @Inject protected ProducerTemplate producer;

  @Override
  public AuthResult authenticate() {
    return AuthResult.notRequired();
  }

  @Override
  public HealthStatus healthCheck() {
    return HealthStatus.healthy();
  }

  @Override
  public Capabilities discoverCapabilities() {
    ConnectorDescriptor d = descriptor();
    return new Capabilities(
        d.supportedEntities(), List.of(d.pollingOrEventMode().name().toLowerCase()), null, null);
  }

  @Override
  public IngestResult ingest(IngestRequest request) {
    return IngestResult.empty("ingestão disparada pelas rotas Camel do conector");
  }

  /** Entrega uma mensagem bruta ao pipeline padrão (síncrono). */
  protected String submit(RawMessage raw) {
    String correlationId = Ids.correlation();
    producer.sendBodyAndHeader(
        ConnectorRuntime.INGEST, raw, PipelineHeaders.CORRELATION_ID, correlationId);
    return correlationId;
  }

  @Override
  public PublishResult publish(CanonicalBatch batch) {
    String correlationId =
        batch.attributes().getOrDefault(CanonicalBatch.CORRELATION_ID, Ids.correlation());
    return corePublisher.publish(descriptor(), batch, correlationId);
  }

  @Override
  public ReconciliationReport reconcile(Period period) {
    return new ReconciliationJob(descriptor(), sourceCounter(), ledger, metrics).run(period);
  }

  /**
   * Contador na fonte; por padrão desconhecido (0), o que registra gap quando houver publicações.
   */
  protected SourceCounter sourceCounter() {
    return (entity, period) -> ledger.countPublished(entity, period);
  }

  @Override
  public RetryDecision retry(FailedMessage failed) {
    return retryPolicy.decide(failed);
  }

  @Override
  public ErrorDetails getErrorDetails(String messageId) {
    return ledger
        .findById(messageId)
        .map(m -> m.lastError() == null ? ErrorDetails.none(messageId) : m.lastError())
        .orElse(ErrorDetails.none(messageId));
  }

  @Override
  public void emitMetrics(MetricsSink sink) {
    ConnectorDescriptor d = descriptor();
    sink.gauge(
        "connector_info",
        1,
        "connector_id",
        d.connectorId(),
        "connector_version",
        d.connectorVersion());
  }
}
