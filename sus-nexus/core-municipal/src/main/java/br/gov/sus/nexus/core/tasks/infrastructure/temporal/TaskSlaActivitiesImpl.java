package br.gov.sus.nexus.core.tasks.infrastructure.temporal;

import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import br.gov.sus.nexus.core.tasks.api.TaskCommands;
import br.gov.sus.nexus.core.tasks.api.TaskDto;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Activities executadas no worker: tenant do input aplicado via {@link TenantTransactions#runAs}.
 */
@ApplicationScoped
public class TaskSlaActivitiesImpl implements TaskSlaActivities {

  @Inject TenantTransactions transactions;
  @Inject TaskCommands commands;
  @Inject TaskQueries queries;

  @Override
  public boolean isOpen(String tenantId, String taskId) {
    return transactions.runAs(
        tenantId,
        () -> {
          try {
            return !queries.get(taskId).status().isFinal();
          } catch (RuntimeException e) {
            return false;
          }
        });
  }

  @Override
  public boolean breachSla(String tenantId, String taskId) {
    Optional<TaskDto> result = transactions.runAs(tenantId, () -> commands.breachSla(taskId));
    return result.isPresent();
  }
}
