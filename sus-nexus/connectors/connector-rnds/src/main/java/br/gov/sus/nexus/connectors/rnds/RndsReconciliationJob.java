package br.gov.sus.nexus.connectors.rnds;

import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmission;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStatus;
import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStore;
import br.gov.sus.nexus.connectors.sdk.api.Period;
import br.gov.sus.nexus.connectors.sdk.api.ReconciliationReport;
import br.gov.sus.nexus.connectors.sdk.metrics.ConnectorMetrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Reconciliação de envios à RNDS por modelo e período: {@code source_count} = enviados (ao menos um
 * POST ao EHR) e {@code bus_count} = aceitos (2xx com protocolo). O gap (rejeitados + falhas após
 * envio + em retry) alimenta {@code integration_reconciliation_gap_total} e é publicado no core
 * ({@code POST /api/v1/integration/reconciliation}) pelo agendamento em {@link RndsRoutes}.
 */
public class RndsReconciliationJob {

  private static final Logger LOG = Logger.getLogger(RndsReconciliationJob.class);

  private final RndsConnector connector;
  private final RndsSubmissionStore store;
  private final ConnectorMetrics metrics;

  public RndsReconciliationJob(
      RndsConnector connector, RndsSubmissionStore store, ConnectorMetrics metrics) {
    this.connector = connector;
    this.store = store;
    this.metrics = metrics;
  }

  /** Contagens de um modelo no período. */
  public record Counts(long sent, long accepted, long rejected, long invalid, long failed) {}

  public Counts counts(String model, Period period) {
    List<RndsSubmission> list = store.list(model, period);
    long sent = list.stream().filter(s -> s.attempts() > 0).count();
    return new Counts(
        sent,
        count(list, RndsSubmissionStatus.ACCEPTED),
        count(list, RndsSubmissionStatus.REJECTED),
        count(list, RndsSubmissionStatus.INVALID),
        count(list, RndsSubmissionStatus.FAILED));
  }

  public ReconciliationReport run(Period period) {
    List<ReconciliationReport.Entry> entries = new ArrayList<>();
    for (String model : connector.models()) {
      Counts c = counts(model, period);
      String entity = RndsConnector.entityType(model);
      ReconciliationReport.Entry entry =
          new ReconciliationReport.Entry(entity, c.sent(), c.accepted());
      entries.add(entry);
      if (entry.gap() != 0) {
        metrics.reconciliationGap(RndsConnector.CONNECTOR_ID, entity, entry.gap());
        LOG.warnf(
            "reconciliação RNDS %s: enviados=%d aceitos=%d rejeitados=%d falhas=%d",
            model, c.sent(), c.accepted(), c.rejected(), c.failed());
      }
    }
    return new ReconciliationReport(RndsConnector.CONNECTOR_ID, period, entries, Instant.now());
  }

  private static long count(List<RndsSubmission> list, RndsSubmissionStatus status) {
    return list.stream().filter(s -> s.status() == status).count();
  }
}
