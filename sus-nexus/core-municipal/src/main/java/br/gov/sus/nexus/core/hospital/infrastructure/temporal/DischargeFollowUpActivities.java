package br.gov.sus.nexus.core.hospital.infrastructure.temporal;

import io.temporal.activity.ActivityInterface;

/** Activities (idempotentes) do {@link DischargeFollowUpWorkflow}. */
@ActivityInterface(namePrefix = "Hospital")
public interface DischargeFollowUpActivities {

  /** Estado persistido do acompanhamento (reconciliação). */
  record Snapshot(String status, boolean contacted, boolean closed) {}

  Snapshot snapshot(String tenantId, String episodeId);

  boolean escalate(String tenantId, String episodeId);

  boolean closeNotFound(String tenantId, String episodeId);
}
