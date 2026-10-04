package br.gov.sus.nexus.core.tasks.infrastructure.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;

/** Implementação determinística de {@link TaskSlaWorkflow}: toda I/O em activities idempotentes. */
public class TaskSlaWorkflowImpl implements TaskSlaWorkflow {

  private final TaskSlaActivities activities =
      Workflow.newActivityStub(
          TaskSlaActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
              .build());

  private boolean done;
  private String outcome = "pending";

  @Override
  public String run(Input input) {
    int version = Workflow.getVersion("task-sla", Workflow.DEFAULT_VERSION, 1);
    if (version < 1) {
      return "unsupported";
    }
    long now = Workflow.currentTimeMillis();
    long due = Instant.parse(input.dueAt()).toEpochMilli();
    Duration wait = Duration.ofMillis(Math.max(0, due - now));
    boolean signalled = Workflow.await(wait, () -> done);
    if (signalled) {
      return outcome;
    }
    if (!activities.isOpen(input.tenantId(), input.taskId())) {
      return "closed";
    }
    boolean breached = activities.breachSla(input.tenantId(), input.taskId());
    return breached ? "sla_breached" : "closed";
  }

  @Override
  public void completed() {
    done = true;
    outcome = "completed";
  }

  @Override
  public void cancelled() {
    done = true;
    outcome = "cancelled";
  }
}
