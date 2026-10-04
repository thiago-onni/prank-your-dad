package br.gov.sus.nexus.core.tasks.application;

import br.gov.sus.nexus.core.audit.api.AuditEntry;
import br.gov.sus.nexus.core.audit.api.AuditService;
import br.gov.sus.nexus.core.platform.errors.ConflictException;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.tasks.api.Assignee;
import br.gov.sus.nexus.core.tasks.api.SlaPolicyDto;
import br.gov.sus.nexus.core.tasks.api.TaskCommands;
import br.gov.sus.nexus.core.tasks.api.TaskCreate;
import br.gov.sus.nexus.core.tasks.api.TaskDto;
import br.gov.sus.nexus.core.tasks.api.TaskOrigin;
import br.gov.sus.nexus.core.tasks.api.TaskPriority;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import br.gov.sus.nexus.core.tasks.api.TaskStatus;
import br.gov.sus.nexus.core.tasks.api.TaskTransition;
import br.gov.sus.nexus.core.tasks.api.TaskType;
import br.gov.sus.nexus.core.tasks.domain.CareTask;
import br.gov.sus.nexus.core.tasks.domain.TaskHistory;
import br.gov.sus.nexus.core.tasks.domain.TaskStateMachine;
import br.gov.sus.nexus.core.tasks.infrastructure.CareTaskRepository;
import br.gov.sus.nexus.core.tasks.infrastructure.SlaPolicyRepository;
import br.gov.sus.nexus.core.tasks.infrastructure.TaskHistoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tarefas: criação com SLA, máquina de estados, escalonamento e eventos {@code sus.task.*}. */
@ApplicationScoped
public class TaskServiceImpl implements TaskCommands, TaskQueries {

  @Inject CareTaskRepository tasks;
  @Inject TaskHistoryRepository history;
  @Inject SlaPolicyRepository policies;
  @Inject TaskEvents events;
  @Inject AuditService audit;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;

  @Override
  @TenantTransactional
  public TaskDto create(TaskCreate c, String causationId) {
    Instant now = Instant.now();
    CareTask t = new CareTask();
    t.id = Ulid.generate(Ulid.TASK);
    t.tenantId = tenantContext.require();
    t.taskType = c.taskType().wire();
    t.priority = c.priority().wire();
    t.title = c.title().trim();
    t.description = c.description();
    t.citizenId = c.citizenId();
    if (c.assignee() != null) {
      t.assigneeKind = c.assignee().kind();
      t.assigneeId = c.assignee().id();
    }
    t.status = (t.assigneeKind == null ? TaskStatus.OPEN : TaskStatus.ASSIGNED).wire();
    if (c.origin() != null) {
      t.originKind = c.origin().kind();
      t.originId = c.origin().id();
      t.originVersion = c.origin().version();
    }
    Optional<SlaPolicyDto> policy =
        c.slaPolicyId() != null
            ? policies.findById(c.slaPolicyId())
            : policies.resolve(c.taskType(), c.priority());
    if (c.slaPolicyId() != null && policy.isEmpty()) {
      throw DomainValidationException.field("sla_policy_id", "política de SLA desconhecida");
    }
    t.slaPolicyId = policy.map(SlaPolicyDto::id).orElse(null);
    t.dueAt =
        c.dueAt() != null
            ? c.dueAt().toInstant()
            : policy.map(p -> now.plus(p.dueIn())).orElse(null);
    t.createdBy = currentActor.actorId();
    t.createdAt = now;
    t.updatedAt = now;
    tasks.persist(t);
    record(t, "created", null, null, null);
    events.publish("created", t, causationId);
    return toDto(t);
  }

  @Override
  @TenantTransactional
  public TaskDto transition(String taskId, TaskTransition tr) {
    CareTask t = load(taskId);
    TaskStatus current = TaskStatus.fromWire(t.status);
    if (!TaskStateMachine.canApply(current, tr.action())) {
      throw new ConflictException(
          "transição " + tr.action().wire() + " inválida no estado " + current.wire());
    }
    apply(t, tr, current);
    return toDto(t);
  }

  private void apply(CareTask t, TaskTransition tr, TaskStatus current) {
    Instant now = Instant.now();
    TaskStatus next = TaskStateMachine.next(tr.action());
    switch (tr.action()) {
      case ASSIGN -> {
        if (tr.assignee() == null) {
          throw DomainValidationException.field("assignee", "obrigatório para assign");
        }
        t.assigneeKind = tr.assignee().kind();
        t.assigneeId = tr.assignee().id();
      }
      case ESCALATE -> {
        Assignee to =
            tr.assignee() != null
                ? tr.assignee()
                : policyOf(t).map(SlaPolicyDto::escalateTo).orElse(null);
        if (to != null) {
          t.assigneeKind = to.kind();
          t.assigneeId = to.id();
        }
      }
      case COMPLETE -> {
        t.outcome = tr.outcome();
        t.completedAt = now;
      }
      case CANCEL -> t.completedAt = now;
      case START -> {
        // sem mudança de atribuição
      }
    }
    if (tr.reason() != null) {
      t.reason = tr.reason();
    }
    t.status = next.wire();
    t.updatedAt = now;
    record(t, tr.action().wire(), current.wire(), tr.outcome(), tr.reason());
    if (tr.action() == TaskTransition.Action.COMPLETE
        || tr.action() == TaskTransition.Action.CANCEL
        || tr.action() == TaskTransition.Action.ESCALATE) {
      audit.record(
          AuditEntry.of("task." + tr.action().wire(), "care_task", t.id, t.citizenId)
              .withReason(tr.reason())
              .withDetails(Map.of("previous_status", current.wire(), "status", t.status)));
    }
    String action = TaskStateMachine.eventAction(tr.action());
    if (action != null) {
      events.publish(action, t, null);
    }
  }

  @Override
  @TenantTransactional
  public Optional<TaskDto> breachSla(String taskId) {
    CareTask t = tasks.findById(taskId);
    if (t == null) {
      return Optional.empty();
    }
    TaskStatus current = TaskStatus.fromWire(t.status);
    if (current.isFinal()) {
      return Optional.empty();
    }
    if (t.slaBreachedAt != null) {
      return Optional.of(toDto(t)); // idempotente
    }
    Instant now = Instant.now();
    t.slaBreachedAt = now;
    t.updatedAt = now;
    record(t, "sla_breached", current.wire(), null, "prazo " + t.dueAt + " expirado");
    String breachEventId = events.publish("sla_breached", t, null);
    if (current != TaskStatus.ESCALATED) {
      Assignee to = policyOf(t).map(SlaPolicyDto::escalateTo).orElse(null);
      TaskTransition esc =
          new TaskTransition(TaskTransition.Action.ESCALATE, to, null, "SLA estourado");
      // escalonamento causado pelo estouro (causation = evento sla_breached)
      applyEscalation(t, esc, current, breachEventId);
    }
    return Optional.of(toDto(t));
  }

  private void applyEscalation(CareTask t, TaskTransition esc, TaskStatus current, String cause) {
    Instant now = Instant.now();
    if (esc.assignee() != null) {
      t.assigneeKind = esc.assignee().kind();
      t.assigneeId = esc.assignee().id();
    }
    t.status = TaskStatus.ESCALATED.wire();
    t.reason = esc.reason();
    t.updatedAt = now;
    record(t, "escalate", current.wire(), null, esc.reason());
    audit.record(
        AuditEntry.of("task.escalate", "care_task", t.id, t.citizenId)
            .withReason(esc.reason())
            .withDetails(Map.of("previous_status", current.wire(), "status", t.status)));
    events.publish("escalated", t, cause);
  }

  @Override
  @TenantTransactional
  public Optional<TaskDto> completeByOrigin(
      String originKind, String originId, String outcome, String reason) {
    return tasks
        .findOpenByOrigin(originKind, originId)
        .map(
            t -> {
              apply(t, TaskTransition.complete(outcome, reason), TaskStatus.fromWire(t.status));
              return toDto(t);
            });
  }

  // ---------------------------------------------------------------------
  // consultas
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public TaskDto get(String taskId) {
    return toDto(load(taskId));
  }

  @Override
  @TenantTransactional
  public Page<TaskDto> list(
      TaskStatus status,
      TaskType taskType,
      String assigneeKind,
      String assigneeId,
      String citizenId,
      Boolean overdue,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    List<TaskDto> rows =
        tasks
            .list(
                status == null ? null : status.wire(),
                taskType == null ? null : taskType.wire(),
                blank(assigneeKind),
                blank(assigneeId),
                blank(citizenId),
                overdue,
                Cursor.decode(cursor).orElse(null),
                size + 1)
            .stream()
            .map(TaskServiceImpl::toDto)
            .toList();
    return Page.of(rows, size, TaskDto::id);
  }

  @Override
  @TenantTransactional
  public long countOpen(String citizenId) {
    return tasks.countOpen(citizenId);
  }

  @Override
  @TenantTransactional
  public Optional<TaskDto> findOpenByOrigin(String originKind, String originId) {
    return tasks.findOpenByOrigin(originKind, originId).map(TaskServiceImpl::toDto);
  }

  @Override
  @TenantTransactional
  public Optional<SlaPolicyDto> slaPolicy(TaskType taskType, TaskPriority priority) {
    return policies.resolve(taskType, priority);
  }

  // ---------------------------------------------------------------------

  private Optional<SlaPolicyDto> policyOf(CareTask t) {
    if (t.slaPolicyId != null) {
      Optional<SlaPolicyDto> p = policies.findById(t.slaPolicyId);
      if (p.isPresent()) {
        return p;
      }
    }
    return policies.resolve(TaskType.fromWire(t.taskType), TaskPriority.fromWire(t.priority));
  }

  private void record(CareTask t, String action, String previous, String outcome, String reason) {
    TaskHistory h = new TaskHistory();
    h.id = Ulid.generate(Ulid.TASK_HISTORY);
    h.tenantId = t.tenantId;
    h.taskId = t.id;
    h.action = action;
    h.previousStatus = previous;
    h.status = t.status;
    h.assigneeKind = t.assigneeKind;
    h.assigneeId = t.assigneeId;
    h.outcome = outcome;
    h.reason = reason;
    h.actorId = currentActor.actorId();
    history.persist(h);
  }

  private CareTask load(String id) {
    CareTask t = tasks.findById(id);
    if (t == null) {
      throw new NotFoundException("tarefa", id);
    }
    return t;
  }

  static TaskDto toDto(CareTask t) {
    TaskStatus status = TaskStatus.fromWire(t.status);
    boolean overdue = t.dueAt != null && !status.isFinal() && t.dueAt.isBefore(Instant.now());
    return new TaskDto(
        t.id,
        TaskType.fromWire(t.taskType),
        status,
        TaskPriority.fromWire(t.priority),
        t.title,
        t.description,
        t.citizenId,
        t.assigneeKind == null ? null : new Assignee(t.assigneeKind, t.assigneeId),
        offset(t.dueAt),
        t.slaPolicyId,
        overdue,
        offset(t.slaBreachedAt),
        t.originKind == null ? null : new TaskOrigin(t.originKind, t.originId, t.originVersion),
        t.outcome,
        t.reason,
        offset(t.createdAt),
        offset(t.updatedAt),
        offset(t.completedAt),
        t.version);
  }

  private static OffsetDateTime offset(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
