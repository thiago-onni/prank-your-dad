package br.gov.sus.nexus.connectors.rnds;

import br.gov.sus.nexus.connectors.rnds.submission.RndsSubmissionStore;
import br.gov.sus.nexus.connectors.sdk.api.Period;
import br.gov.sus.nexus.connectors.sdk.api.ReconciliationReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.builder.RouteBuilder;

/**
 * Agendamentos do conector (Camel timer): heartbeat no core com contagens das últimas 24 h e
 * reconciliação enviados × aceitos por modelo (publicada no core).
 */
@ApplicationScoped
public class RndsRoutes extends RouteBuilder {

  private final RndsConfig config;
  private final RndsConnector connector;
  private final RndsSubmissionStore submissions;
  private final CoreMirror core;

  @Inject
  public RndsRoutes(
      RndsConfig config,
      RndsConnector connector,
      RndsSubmissionStore submissions,
      CoreMirror core) {
    this.config = config;
    this.connector = connector;
    this.submissions = submissions;
    this.core = core;
  }

  @Override
  public void configure() {
    if (config.heartbeat().enabled()) {
      from("timer:rnds-heartbeat?delay=5000&period=" + config.heartbeat().periodMs())
          .routeId("rnds-heartbeat")
          .process(e -> heartbeat());
    }
    if (config.reconciliation().enabled()) {
      from("timer:rnds-reconciliation?delay=60000&period=" + config.reconciliation().periodMs())
          .routeId("rnds-reconciliation")
          .process(e -> reconcile());
    }
  }

  public void heartbeat() {
    Map<String, Object> metrics = new LinkedHashMap<>();
    RndsReconciliationJob job = new RndsReconciliationJob(connector, submissions, null);
    Period last24h = Period.last(Duration.ofHours(24));
    for (String model : connector.models()) {
      RndsReconciliationJob.Counts c = job.counts(model, last24h);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("enabled", connector.enabled(model));
      m.put("sent_24h", c.sent());
      m.put("accepted_24h", c.accepted());
      m.put("rejected_24h", c.rejected());
      m.put("invalid_24h", c.invalid());
      m.put("failed_24h", c.failed());
      metrics.put(model, m);
    }
    core.heartbeat(connector.descriptor(), connector.healthCheck(), metrics);
  }

  public ReconciliationReport reconcile() {
    ReconciliationReport report =
        connector.reconcile(Period.last(config.reconciliation().window()));
    core.reconciliation(report);
    return report;
  }
}
