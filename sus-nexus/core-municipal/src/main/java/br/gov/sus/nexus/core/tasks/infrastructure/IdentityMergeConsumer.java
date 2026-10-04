package br.gov.sus.nexus.core.tasks.infrastructure;

import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import br.gov.sus.nexus.core.tasks.api.SlaPolicyDto;
import br.gov.sus.nexus.core.tasks.api.TaskCommands;
import br.gov.sus.nexus.core.tasks.api.TaskDto;
import br.gov.sus.nexus.core.tasks.api.TaskPriority;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import br.gov.sus.nexus.core.tasks.api.TaskType;
import br.gov.sus.nexus.core.tasks.application.MpiReviewTasks;
import br.gov.sus.nexus.core.tasks.infrastructure.temporal.TaskWorkflowStarter;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consome {@code sus.identity.merge.v1} (canal {@code tasks-merge-in}): {@code case_opened} gera a
 * tarefa {@code mpi_review} (fila {@code cadastro_mestre}) e inicia {@code MpiReviewWorkflow};
 * {@code merged|rejected|unmerged} concluem a tarefa e sinalizam o workflow. Desacoplado do módulo
 * identity por evento; idempotente via {@code event_inbox}.
 */
@ApplicationScoped
public class IdentityMergeConsumer {

  public static final String CONSUMER_GROUP = "core-tasks-merge";

  @Inject InboundEventProcessor processor;
  @Inject MpiReviewTasks reviewTasks;
  @Inject TaskCommands commands;
  @Inject TaskQueries queries;
  @Inject TaskWorkflowStarter starter;

  @Incoming("tasks-merge-in")
  @Blocking
  public void onMergeEvent(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          JsonNode data = in.data();
          String caseId = data.path("case_id").asText(null);
          if (caseId == null) {
            return Boolean.FALSE;
          }
          String citizenId = data.path("surviving_citizen_id").asText(in.citizenId());
          switch (in.action()) {
            case "case_opened" -> {
              TaskDto task = reviewTasks.ensure(caseId, citizenId, in.eventId());
              Duration sla =
                  queries
                      .slaPolicy(TaskType.MPI_REVIEW, TaskPriority.MEDIUM)
                      .map(SlaPolicyDto::dueIn)
                      .orElse(Duration.ofDays(5));
              starter.startMpiReview(in.tenantId(), caseId, task.citizenId(), sla);
            }
            case "merged", "rejected", "unmerged" -> {
              commands.completeByOrigin(
                  MpiReviewTasks.ORIGIN_KIND, caseId, in.action(), "caso decidido: " + in.action());
              starter.signalMpiDecided(caseId, in.action());
            }
            default -> {
              // outras ações não interessam ao módulo tasks
            }
          }
          return Boolean.TRUE;
        });
  }
}
