package br.gov.sus.nexus.core.regulation.infrastructure.temporal;

import br.gov.sus.nexus.core.regulation.api.RegulationStatus;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;

/** Implementação determinística de {@link RegulationSlaWorkflow}: toda I/O em activities. */
public class RegulationSlaWorkflowImpl implements RegulationSlaWorkflow {

  private final RegulationSlaActivities activities =
      Workflow.newActivityStub(
          RegulationSlaActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
              .build());

  private boolean decided;
  private String lastStatus;

  @Override
  public String run(Input input) {
    int version = Workflow.getVersion("regulation-sla", Workflow.DEFAULT_VERSION, 1);
    if (version < 1) {
      return "unsupported";
    }
    long start = Instant.parse(input.requestedAt()).toEpochMilli();
    long due = Instant.parse(input.slaDueAt()).toEpochMilli();
    long half = start + (due - start) / 2;

    if (Workflow.await(until(half), () -> decided)) {
      return "decided:" + lastStatus;
    }
    if (!activities.isAwaitingDecision(input.tenantId(), input.requestId())) {
      return "closed";
    }
    activities.halfSla(input.tenantId(), input.requestId());

    if (Workflow.await(until(due), () -> decided)) {
      return "decided:" + lastStatus;
    }
    if (!activities.isAwaitingDecision(input.tenantId(), input.requestId())) {
      return "closed";
    }
    boolean breached = activities.breachSla(input.tenantId(), input.requestId());
    return breached ? "sla_breached" : "closed";
  }

  private static Duration until(long epochMillis) {
    return Duration.ofMillis(Math.max(0, epochMillis - Workflow.currentTimeMillis()));
  }

  @Override
  public void statusChanged(String status) {
    lastStatus = status;
    try {
      if (RegulationStatus.fromWire(status).isDecided()) {
        decided = true;
      }
    } catch (IllegalArgumentException e) {
      // status desconhecido: ignora
    }
  }
}
