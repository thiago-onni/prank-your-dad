package br.gov.sus.nexus.core.tasks.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import br.gov.sus.nexus.core.tasks.api.TaskCommands;
import br.gov.sus.nexus.core.tasks.api.TaskDto;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import br.gov.sus.nexus.core.tasks.application.MpiReviewTasks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/** Activities de revisão do MPI executadas no worker. */
@ApplicationScoped
public class MpiReviewActivitiesImpl implements MpiReviewActivities {

  @Inject TenantTransactions transactions;
  @Inject TaskCommands commands;
  @Inject TaskQueries queries;
  @Inject MpiReviewTasks reviewTasks;

  @Override
  public String ensureReviewTask(String tenantId, String caseId, String citizenId) {
    return transactions.runAs(tenantId, () -> reviewTasks.ensure(caseId, citizenId, null).id());
  }

  @Override
  public boolean escalateReview(String tenantId, String caseId) {
    return transactions.runAs(
        tenantId,
        () -> {
          Optional<TaskDto> task = queries.findOpenByOrigin(MpiReviewTasks.ORIGIN_KIND, caseId);
          return task.flatMap(t -> commands.breachSla(t.id())).isPresent();
        });
  }

  @Override
  public boolean closeReviewTask(String tenantId, String caseId, String decision) {
    return transactions.runAs(
        tenantId,
        () ->
            commands
                .completeByOrigin(
                    MpiReviewTasks.ORIGIN_KIND, caseId, decision, "caso decidido: " + decision)
                .isPresent());
  }
}
