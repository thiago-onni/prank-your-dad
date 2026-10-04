package br.gov.sus.nexus.core.tasks.application;

import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.tasks.api.Assignee;
import br.gov.sus.nexus.core.tasks.api.TaskCommands;
import br.gov.sus.nexus.core.tasks.api.TaskCreate;
import br.gov.sus.nexus.core.tasks.api.TaskDto;
import br.gov.sus.nexus.core.tasks.api.TaskOrigin;
import br.gov.sus.nexus.core.tasks.api.TaskPriority;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import br.gov.sus.nexus.core.tasks.api.TaskType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Tarefa {@code mpi_review} na fila {@code cadastro_mestre} para cada caso de fusão aberto. Origem
 * {@code rule}/{@code <case_id>} garante idempotência (uma tarefa aberta por caso).
 */
@ApplicationScoped
public class MpiReviewTasks {

  public static final String ORIGIN_KIND = "rule";
  public static final String QUEUE = "cadastro_mestre";
  static final String RULE_VERSION = "mpi.case_opened/1.0";

  @Inject TaskCommands commands;
  @Inject TaskQueries queries;

  @TenantTransactional
  public TaskDto ensure(String caseId, String citizenId, String causationId) {
    return queries
        .findOpenByOrigin(ORIGIN_KIND, caseId)
        .orElseGet(
            () ->
                commands.create(
                    new TaskCreate(
                        TaskType.MPI_REVIEW,
                        TaskPriority.MEDIUM,
                        "Revisar caso de identidade " + caseId,
                        "Caso de duplicidade/conflito aberto pelo MPI; decidir fusão, rejeição ou"
                            + " complementação cadastral.",
                        citizenId,
                        Assignee.queue(QUEUE),
                        null,
                        null,
                        new TaskOrigin(ORIGIN_KIND, caseId, RULE_VERSION)),
                    causationId));
  }
}
