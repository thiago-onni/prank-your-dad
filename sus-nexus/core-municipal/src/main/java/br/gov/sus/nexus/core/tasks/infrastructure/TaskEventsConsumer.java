package br.gov.sus.nexus.core.tasks.infrastructure;

import br.gov.sus.nexus.core.platform.events.InboundEventProcessor;
import br.gov.sus.nexus.core.tasks.infrastructure.temporal.TaskWorkflowStarter;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Consome {@code sus.task.v1} (canal {@code tasks-task-in}) para acionar o {@code TaskSlaWorkflow}
 * em {@code created} e sinalizá-lo em {@code completed}/{@code cancelled}. O starter fica no
 * consumidor (não no serviço) para funcionar em replay com {@code workflowId} determinístico.
 */
@ApplicationScoped
public class TaskEventsConsumer {

  public static final String CONSUMER_GROUP = "core-tasks-sla";

  @Inject InboundEventProcessor processor;
  @Inject TaskWorkflowStarter starter;

  @Incoming("tasks-task-in")
  @Blocking
  public void onTaskEvent(String payload) {
    processor.process(
        payload,
        CONSUMER_GROUP,
        in -> {
          JsonNode data = in.data();
          String taskId = data.path("task_id").asText(null);
          if (taskId == null) {
            return Boolean.FALSE;
          }
          switch (in.action()) {
            case "created" -> {
              String dueAt = data.path("due_at").asText(null);
              starter.startTaskSla(
                  in.tenantId(),
                  taskId,
                  dueAt == null ? null : OffsetDateTime.parse(dueAt).toInstant(),
                  data.path("sla_policy_id").asText(null));
            }
            case "completed", "cancelled" -> starter.signalTask(taskId, in.action());
            default -> {
              // assigned/escalated/sla_breached não alteram o ciclo do workflow
            }
          }
          return Boolean.TRUE;
        });
  }
}
