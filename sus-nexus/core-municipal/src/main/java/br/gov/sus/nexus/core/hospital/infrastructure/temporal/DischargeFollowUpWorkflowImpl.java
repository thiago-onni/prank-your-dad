package br.gov.sus.nexus.core.hospital.infrastructure.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;

/** Implementação determinística de {@link DischargeFollowUpWorkflow}: toda I/O em activities. */
public class DischargeFollowUpWorkflowImpl implements DischargeFollowUpWorkflow {

  private final DischargeFollowUpActivities activities =
      Workflow.newActivityStub(
          DischargeFollowUpActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
              .build());

  private boolean contacted;
  private boolean closed;
  private String outcome;

  @Override
  public String run(Input input) {
    int version = Workflow.getVersion("discharge-followup", Workflow.DEFAULT_VERSION, 1);
    if (version < 1) {
      return "unsupported";
    }
    refresh(input);
    if (contacted || closed) {
      return result();
    }

    // 1. prazo de contato (SLA por risco)
    long deadline = Instant.parse(input.dueAt()).toEpochMilli();
    boolean ok = Workflow.await(until(deadline), () -> contacted || closed);
    if (!ok) {
      refresh(input);
      if (!contacted && !closed) {
        activities.escalate(input.tenantId(), input.episodeId());
      }
    }
    if (contacted || closed) {
      return result();
    }

    // 2. segundo prazo (busca ativa) → not_found
    ok =
        Workflow.await(
            Duration.ofSeconds(Math.max(1, input.secondDeadlineSeconds())),
            () -> contacted || closed);
    if (!ok) {
      refresh(input);
      if (!contacted && !closed) {
        activities.closeNotFound(input.tenantId(), input.episodeId());
        return "not_found";
      }
    }
    return result();
  }

  private String result() {
    return (contacted ? "contacted:" : "closed:") + (outcome == null ? "unknown" : outcome);
  }

  /** Reconcilia com o estado persistido (sinais perdidos/anteriores ao start). */
  private void refresh(Input input) {
    DischargeFollowUpActivities.Snapshot s =
        activities.snapshot(input.tenantId(), input.episodeId());
    if (s == null) {
      return;
    }
    if (s.contacted()) {
      contacted = true;
      outcome = s.status();
    }
    if (s.closed()) {
      closed = true;
      outcome = s.status();
    }
  }

  private static Duration until(long epochMillis) {
    return Duration.ofMillis(Math.max(0, epochMillis - Workflow.currentTimeMillis()));
  }

  @Override
  public void contacted(String outcome) {
    this.outcome = outcome;
    if ("contact_made".equals(outcome) || "appointment_scheduled".equals(outcome)) {
      contacted = true;
    } else {
      closed = true;
    }
  }
}
