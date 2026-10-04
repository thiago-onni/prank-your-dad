package br.gov.sus.nexus.core.exams.infrastructure.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;

/** Implementação determinística de {@link ExamFollowUpWorkflow}: toda I/O em activities. */
public class ExamFollowUpWorkflowImpl implements ExamFollowUpWorkflow {

  private final ExamFollowUpActivities activities =
      Workflow.newActivityStub(
          ExamFollowUpActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
              .build());

  private boolean scheduled;
  private boolean performed;
  private boolean reported;
  private boolean noShowPending;
  private boolean followupDone;
  private boolean terminal;
  private String closedStatus;

  @Override
  public String run(Input input) {
    int version = Workflow.getVersion("exam-followup", Workflow.DEFAULT_VERSION, 1);
    if (version < 1) {
      return "unsupported";
    }
    refresh(input);

    // 1. agendamento (EXA-004)
    if (!scheduled && !performed && !reported && !terminal) {
      long deadline =
          Instant.parse(input.requestedAt())
              .plus(Duration.ofDays(input.notScheduledDays()))
              .toEpochMilli();
      boolean ok =
          Workflow.await(until(deadline), () -> scheduled || performed || reported || terminal);
      if (!ok) {
        refresh(input);
        if (!scheduled && !performed && !reported && !terminal) {
          activities.flagNotScheduled(input.tenantId(), input.orderId());
          Workflow.await(() -> scheduled || performed || reported || terminal);
        }
      }
    }
    if (terminal) {
      return "closed:" + closedStatus;
    }

    // 2. realização (faltas → busca ativa)
    while (!performed && !reported && !terminal) {
      Workflow.await(() -> performed || reported || terminal || noShowPending);
      if (noShowPending) {
        noShowPending = false;
        activities.noShowRecovery(input.tenantId(), input.orderId());
      }
    }
    if (terminal) {
      return "closed:" + closedStatus;
    }

    // 3. laudo (EXA-005)
    if (!reported) {
      boolean ok =
          Workflow.await(Duration.ofDays(input.resultPendingDays()), () -> reported || terminal);
      if (!ok) {
        refresh(input);
        if (!reported && !terminal) {
          activities.flagResultPending(input.tenantId(), input.orderId());
          Workflow.await(() -> reported || terminal);
        }
      }
    }
    if (terminal) {
      return "closed:" + closedStatus;
    }

    // 4. retorno (EXA-009)
    boolean done =
        Workflow.await(Duration.ofDays(input.followupDays()), () -> followupDone || terminal);
    if (!done) {
      refresh(input);
      if (!followupDone && !terminal) {
        activities.ensureResultFollowup(input.tenantId(), input.orderId());
        Workflow.await(() -> followupDone || terminal);
      }
    }
    return terminal ? "closed:" + closedStatus : "followup_completed";
  }

  /** Reconcilia com o estado persistido (sinais perdidos/anteriores ao start). */
  private void refresh(Input input) {
    ExamFollowUpActivities.Snapshot s = activities.snapshot(input.tenantId(), input.orderId());
    if (s == null) {
      return;
    }
    scheduled |= s.scheduled();
    performed |= s.performed();
    reported |= s.reported();
    followupDone |= s.followupDone();
    if (s.terminal()) {
      terminal = true;
      closedStatus = s.status();
    }
  }

  private static Duration until(long epochMillis) {
    return Duration.ofMillis(Math.max(0, epochMillis - Workflow.currentTimeMillis()));
  }

  @Override
  public void scheduled() {
    scheduled = true;
  }

  @Override
  public void performed() {
    performed = true;
  }

  @Override
  public void reported() {
    reported = true;
    performed = true;
  }

  @Override
  public void noShow() {
    noShowPending = true;
  }

  @Override
  public void followupCompleted() {
    followupDone = true;
  }

  @Override
  public void closed(String status) {
    terminal = true;
    closedStatus = status;
  }
}
