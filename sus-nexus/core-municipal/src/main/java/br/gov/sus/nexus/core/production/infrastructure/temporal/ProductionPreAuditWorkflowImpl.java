package br.gov.sus.nexus.core.production.infrastructure.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;

/** Implementação determinística de {@link ProductionPreAuditWorkflow}: I/O só em activities. */
public class ProductionPreAuditWorkflowImpl implements ProductionPreAuditWorkflow {

  static final String PENDING = "pending";

  private final ProductionPreAuditActivities activities =
      Workflow.newActivityStub(
          ProductionPreAuditActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
              .build());

  private int corrections;

  @Override
  public String run(Input input) {
    int version = Workflow.getVersion("production-preaudit", Workflow.DEFAULT_VERSION, 1);
    if (version < 1) {
      return "unsupported";
    }
    String status = activities.validate(input.tenantId(), input.recordId());
    long deadline = Instant.parse(input.deadlineAt()).toEpochMilli();
    int handled = 0;
    while (PENDING.equals(status)) {
      int seen = handled;
      boolean signalled = Workflow.await(until(deadline), () -> corrections > seen);
      if (!signalled) {
        return "expired:" + activities.expire(input.tenantId(), input.recordId());
      }
      handled = corrections;
      status = activities.validate(input.tenantId(), input.recordId());
    }
    return status;
  }

  private static Duration until(long epochMillis) {
    return Duration.ofMillis(Math.max(0, epochMillis - Workflow.currentTimeMillis()));
  }

  @Override
  public void corrected() {
    corrections++;
  }
}
