package br.gov.sus.nexus.connectors.sdk.reconcile;

import br.gov.sus.nexus.connectors.sdk.api.ConnectorDescriptor;
import br.gov.sus.nexus.connectors.sdk.api.Period;
import br.gov.sus.nexus.connectors.sdk.api.ReconciliationReport;
import br.gov.sus.nexus.connectors.sdk.ledger.IntegrationMessageLedger;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Compara contagens fonte × publicadas por entidade no período (PLANO §7.3). Diferenças alimentam
 * {@code integration_reconciliation_gap_total}. Agendamento (Temporal/cron) fica a cargo do
 * conector.
 */
public class ReconciliationJob {

  private static final Logger LOG = Logger.getLogger(ReconciliationJob.class);

  private final ConnectorDescriptor descriptor;
  private final SourceCounter sourceCounter;
  private final IntegrationMessageLedger ledger;
  private final ConnectorMetrics metrics;

  public ReconciliationJob(
      ConnectorDescriptor descriptor,
      SourceCounter sourceCounter,
      IntegrationMessageLedger ledger,
      ConnectorMetrics metrics) {
    this.descriptor = descriptor;
    this.sourceCounter = sourceCounter;
    this.ledger = ledger;
    this.metrics = metrics;
  }

  public ReconciliationReport run(Period period) {
    List<ReconciliationReport.Entry> entries = new ArrayList<>();
    for (String entity : descriptor.supportedEntities()) {
      long source = sourceCounter.count(entity, period);
      long bus = ledger.countPublished(entity, period);
      ReconciliationReport.Entry entry = new ReconciliationReport.Entry(entity, source, bus);
      entries.add(entry);
      if (entry.gap() != 0) {
        metrics.reconciliationGap(descriptor.connectorId(), entity, entry.gap());
        LOG.warnf(
            "reconciliação %s/%s: fonte=%d publicadas=%d gap=%d",
            descriptor.connectorId(), entity, source, bus, entry.gap());
      }
    }
    return new ReconciliationReport(descriptor.connectorId(), period, entries, Instant.now());
  }
}
