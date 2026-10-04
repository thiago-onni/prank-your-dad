package br.gov.sus.nexus.core.tasks.application;

import br.gov.sus.nexus.core.platform.events.DomainEvent;
import br.gov.sus.nexus.core.platform.events.EventEnvelope;
import br.gov.sus.nexus.core.platform.events.EventPublisher;
import br.gov.sus.nexus.core.platform.security.Purpose;
import br.gov.sus.nexus.core.tasks.domain.CareTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publica {@code sus.task.*} (tópico {@code sus.task.v1}) conforme {@code
 * task/task.v1.schema.json}.
 */
@ApplicationScoped
public class TaskEvents {

  public static final String EVENT_PREFIX = "sus.task.";
  public static final String AGGREGATE_TYPE = "care_task";
  static final String EVENT_VERSION = "1.0";

  @Inject EventPublisher publisher;

  public String publish(String action, CareTask t, String causationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("task_id", t.id);
    data.put("task_type", t.taskType);
    data.put("status", t.status);
    data.put("priority", t.priority);
    if (t.assigneeKind != null && t.assigneeId != null) {
      data.put("assignee", Map.of("kind", t.assigneeKind, "id", t.assigneeId));
    }
    if (t.dueAt != null) {
      data.put("due_at", t.dueAt.atOffset(ZoneOffset.UTC));
    }
    if (t.slaPolicyId != null) {
      data.put("sla_policy_id", t.slaPolicyId);
    }
    if (t.originKind != null) {
      Map<String, Object> origin = new LinkedHashMap<>();
      origin.put("kind", t.originKind);
      if (t.originId != null) {
        origin.put("id", t.originId);
      }
      if (t.originVersion != null) {
        origin.put("version", t.originVersion);
      }
      data.put("origin", origin);
    }
    if (t.outcome != null) {
      data.put("outcome", t.outcome);
    }
    if (t.reason != null) {
      data.put("reason", t.reason.length() > 500 ? t.reason.substring(0, 500) : t.reason);
    }
    EventEnvelope.Subject subject =
        t.citizenId == null ? null : new EventEnvelope.Subject(t.citizenId, null);
    return publisher.publish(
        new DomainEvent(
            AGGREGATE_TYPE,
            t.id,
            EVENT_PREFIX + action,
            EVENT_VERSION,
            OffsetDateTime.now(ZoneOffset.UTC),
            subject,
            new EventEnvelope.Source("core-municipal", "core-municipal", t.id, null, null),
            data,
            new EventEnvelope.Privacy("internal", List.of(Purpose.CARE_COORDINATION.wire())),
            causationId));
  }
}
