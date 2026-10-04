package br.gov.sus.nexus.core.tasks.infrastructure.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/** Implementação determinística de {@link MpiReviewWorkflow}. */
public class MpiReviewWorkflowImpl implements MpiReviewWorkflow {

  private final MpiReviewActivities activities =
      Workflow.newActivityStub(
          MpiReviewActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(5).build())
              .build());

  private String decision;

  @Override
  public String run(Input input) {
    Workflow.getVersion("mpi-review", Workflow.DEFAULT_VERSION, 1);
    activities.ensureReviewTask(input.tenantId(), input.caseId(), input.citizenId());
    boolean signalled =
        Workflow.await(Duration.ofSeconds(input.reviewSlaSeconds()), () -> decision != null);
    if (signalled) {
      activities.closeReviewTask(input.tenantId(), input.caseId(), decision);
      return decision;
    }
    activities.escalateReview(input.tenantId(), input.caseId());
    // continua aguardando a decisão após o escalonamento
    Workflow.await(() -> decision != null);
    activities.closeReviewTask(input.tenantId(), input.caseId(), decision);
    return decision;
  }

  @Override
  public void decided(String value) {
    this.decision = value == null ? "decided" : value;
  }
}
