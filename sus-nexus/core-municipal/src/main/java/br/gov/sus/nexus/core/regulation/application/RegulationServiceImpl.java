package br.gov.sus.nexus.core.regulation.application;

import br.gov.sus.nexus.core.audit.api.AuditEntry;
import br.gov.sus.nexus.core.audit.api.AuditService;
import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.CitizenService;
import br.gov.sus.nexus.core.identity.api.CitizenSummary;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.errors.ProblemException;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.ingestion.UpsertResult;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.security.Roles;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.reference.api.HealthUnitDto;
import br.gov.sus.nexus.core.reference.api.HealthUnitService;
import br.gov.sus.nexus.core.regulation.api.ProviderCapacityDto;
import br.gov.sus.nexus.core.regulation.api.ProviderCapacityUpsert;
import br.gov.sus.nexus.core.regulation.api.RegulationIssueCreate;
import br.gov.sus.nexus.core.regulation.api.RegulationIssueDto;
import br.gov.sus.nexus.core.regulation.api.RegulationIssueKind;
import br.gov.sus.nexus.core.regulation.api.RegulationKind;
import br.gov.sus.nexus.core.regulation.api.RegulationPriority;
import br.gov.sus.nexus.core.regulation.api.RegulationQuery;
import br.gov.sus.nexus.core.regulation.api.RegulationQueueItemDto;
import br.gov.sus.nexus.core.regulation.api.RegulationRequestDto;
import br.gov.sus.nexus.core.regulation.api.RegulationRequestRegistration;
import br.gov.sus.nexus.core.regulation.api.RegulationResult;
import br.gov.sus.nexus.core.regulation.api.RegulationService;
import br.gov.sus.nexus.core.regulation.api.RegulationStatus;
import br.gov.sus.nexus.core.regulation.api.RegulationStatusChange;
import br.gov.sus.nexus.core.regulation.domain.ProviderCapacity;
import br.gov.sus.nexus.core.regulation.domain.RegulationDecision;
import br.gov.sus.nexus.core.regulation.domain.RegulationIssue;
import br.gov.sus.nexus.core.regulation.domain.RegulationRequest;
import br.gov.sus.nexus.core.regulation.domain.RegulationSourceLink;
import br.gov.sus.nexus.core.regulation.domain.RegulationStatusHistory;
import br.gov.sus.nexus.core.regulation.infrastructure.RegulationQueueRepository;
import br.gov.sus.nexus.core.regulation.infrastructure.RegulationRepositories;
import br.gov.sus.nexus.core.regulation.infrastructure.RegulationSlaPolicyRepository;
import br.gov.sus.nexus.core.scheduling.api.AppointmentDto;
import br.gov.sus.nexus.core.scheduling.api.AppointmentService;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
import br.gov.sus.nexus.core.sharedkernel.Competence;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Fila regulatória espelhada: registro por vínculo de origem, histórico, decisão (somente
 * registro), pendências (REG-005), SLA por prioridade (REG-010), eventos e efeitos derivados
 * (no-show → tarefa). O barramento nunca decide nem muda prioridade (REG-009).
 */
@ApplicationScoped
public class RegulationServiceImpl implements RegulationService {

  private static final Logger LOG = Logger.getLogger(RegulationServiceImpl.class);

  public static final String ISSUE_RULE = "regulation.issues";
  static final String ISSUE_RULE_VERSION = "1.0";
  public static final String NO_SHOW_RULE = "regulation.no_show";
  static final String NO_SHOW_RULE_VERSION = NO_SHOW_RULE + "/1.0";
  public static final String SLA_QUEUE = "regulacao";
  static final Set<String> CODE_SYSTEMS = Set.of("SIGTAP", "LOCAL");

  @Inject RegulationRepositories.Requests requests;
  @Inject RegulationRepositories.History history;
  @Inject RegulationRepositories.Decisions decisions;
  @Inject RegulationRepositories.Issues issues;
  @Inject RegulationRepositories.SourceLinks links;
  @Inject RegulationRepositories.Capacity capacity;
  @Inject RegulationSlaPolicyRepository slaPolicies;
  @Inject RegulationQueueRepository queue;
  @Inject RegulationEvents events;
  @Inject CitizenService citizens;
  @Inject AppointmentService appointments;
  @Inject HealthUnitService healthUnits;
  @Inject TaskCommands taskCommands;
  @Inject TaskQueries taskQueries;
  @Inject AuditService audit;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;

  /** Tipos de pedido em que a ausência de documentos anexos gera pendência (REG-005). */
  @ConfigProperty(
      name = "sus.regulation.documents-required-kinds",
      defaultValue = "procedure,surgery,admission")
  String documentsRequiredKinds;

  // ---------------------------------------------------------------------
  // registro (porta única: REST + ingestão)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public RegulationResult register(RegulationRequestRegistration reg) {
    validate(reg);
    String tenant = tenantContext.require();
    CitizenDetail citizen = resolveCitizen(reg.citizenRef());
    Instant now = Instant.now();
    OffsetDateTime occurredAt =
        reg.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : reg.occurredAt();

    Optional<RegulationSourceLink> link =
        links.findBySource(tenant, reg.source().system(), reg.source().sourceRecordId());
    if (link.isEmpty()) {
      RegulationRequest r = new RegulationRequest();
      r.id = Ulid.generate(Ulid.REGULATION_REQUEST);
      r.tenantId = tenant;
      r.citizenId = citizen.id();
      r.sourceSystem = reg.source().system();
      r.sourceRecordId = reg.source().sourceRecordId();
      r.sourceRecordVersion = reg.source().sourceRecordVersion();
      copy(reg, r);
      applySla(r, true);
      if (RegulationStatus.fromWire(r.status).isDecided()) {
        r.decidedAt = occurredAt.toInstant();
      }
      r.createdAt = now;
      r.updatedAt = now;
      requests.persist(r);

      RegulationSourceLink l = new RegulationSourceLink();
      l.id = Ulid.generate(Ulid.SOURCE_LINK);
      l.tenantId = tenant;
      l.requestId = r.id;
      l.sourceSystem = reg.source().system();
      l.connector = reg.source().connector();
      l.sourceRecordId = reg.source().sourceRecordId();
      l.sourceRecordVersion = reg.source().sourceRecordVersion();
      links.persist(l);

      recordHistory(
          r,
          null,
          reg.decisionReason(),
          actorKind(reg.regulatorId()),
          reg.regulatorId(),
          occurredAt.toInstant(),
          reg.source().system());
      recordDecisionIfAny(
          r,
          reg.regulatorId(),
          reg.decisionReason(),
          occurredAt.toInstant(),
          reg.source().system());
      String eventId =
          events.publishRequest("created", r, reg.source(), occurredAt, reg.decisionReason(), null);
      detectIssues(r);
      afterStatus(r, null, citizen, eventId);
      return new RegulationResult(toDto(r, true), true);
    }

    RegulationRequest r = requests.findById(link.get().requestId);
    if (r == null) {
      throw new NotFoundException("solicitação regulatória", link.get().requestId);
    }
    String previousStatus = r.status;
    boolean changed = copy(reg, r);
    if (!Objects.equals(r.citizenId, citizen.id())) {
      r.citizenId = citizen.id();
      changed = true;
    }
    if (!changed) {
      detectIssues(r);
      return new RegulationResult(toDto(r, true), false);
    }
    r.sourceRecordVersion = reg.source().sourceRecordVersion();
    r.updatedAt = now;
    link.get().sourceRecordVersion = reg.source().sourceRecordVersion();
    link.get().updatedAt = now;
    applySla(r, false);

    boolean statusChanged = !previousStatus.equals(r.status);
    String action = statusChanged ? requestActionFor(r.status) : "updated";
    String eventId =
        events.publishRequest(action, r, reg.source(), occurredAt, reg.decisionReason(), null);
    if (statusChanged) {
      String actorKind = actorKind(reg.regulatorId());
      recordHistory(
          r,
          previousStatus,
          reg.decisionReason(),
          actorKind,
          reg.regulatorId(),
          occurredAt.toInstant(),
          reg.source().system());
      recordDecisionIfAny(
          r,
          reg.regulatorId(),
          reg.decisionReason(),
          occurredAt.toInstant(),
          reg.source().system());
      markDecided(r, occurredAt.toInstant());
      eventId =
          events.publishStatusChanged(
              r,
              previousStatus,
              actorKind,
              reg.regulatorId(),
              reg.source(),
              occurredAt,
              reg.decisionReason(),
              null,
              false,
              eventId);
    }
    detectIssues(r);
    afterStatus(r, previousStatus, citizen, eventId);
    return new RegulationResult(toDto(r, true), false);
  }

  // ---------------------------------------------------------------------
  // mudança de status (sistema oficial)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public RegulationRequestDto changeStatus(String requestId, RegulationStatusChange change) {
    return applyStatusChange(load(requestId), change);
  }

  @Override
  @TenantTransactional
  public RegulationRequestDto changeStatusBySource(
      String sourceSystem, String sourceRecordId, RegulationStatusChange change) {
    RegulationSourceLink link =
        links
            .findBySource(tenantContext.require(), sourceSystem, sourceRecordId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "solicitação regulatória", sourceSystem + "/" + sourceRecordId));
    return applyStatusChange(load(link.requestId), change);
  }

  private RegulationRequestDto applyStatusChange(RegulationRequest r, RegulationStatusChange c) {
    rejectAgents();
    validateCnes("provider_cnes", c.providerCnes());
    Instant now = Instant.now();
    Instant occurred = c.occurredAt().toInstant();
    String previousStatus = r.status;
    RegulationStatus next = c.status();

    boolean changed = set(r.status, next.wire(), v -> r.status = v);
    if (c.priority() != null) {
      changed |= set(r.priority, c.priority().wire(), v -> r.priority = v);
    }
    changed |= set(r.providerCnes, blank(c.providerCnes()), v -> r.providerCnes = v);
    if (c.scheduledAt() != null) {
      changed |= set(r.scheduledAt, micros(c.scheduledAt()), v -> r.scheduledAt = v);
    }
    changed |= set(r.regulatorId, blank(c.regulatorId()), v -> r.regulatorId = v);
    if (c.reason() != null) {
      changed |= set(r.decisionReason, blank(c.reason()), v -> r.decisionReason = v);
    }
    String appointmentSourceId = blank(c.appointmentSourceRecordId());
    if (appointmentSourceId != null) {
      Optional<AppointmentDto> apt =
          appointments.findBySourceRecord(c.source().system(), appointmentSourceId);
      if (apt.isPresent()) {
        changed |= set(r.appointmentId, apt.get().id(), v -> r.appointmentId = v);
        if (r.scheduledAt == null) {
          r.scheduledAt = apt.get().scheduledStart().toInstant();
        }
      } else {
        LOG.debugf("regulação %s: agendamento de origem ainda não conhecido pela agenda", r.id);
      }
    }
    if (!changed) {
      return toDto(r, true);
    }
    r.updatedAt = now;
    applySla(r, false);

    boolean statusChanged = !previousStatus.equals(r.status);
    String actorKind = actorKind(c.regulatorId());
    String actorId = c.regulatorId() != null ? c.regulatorId() : currentActor.actorId();
    String eventId = null;
    if (statusChanged) {
      recordHistory(
          r, previousStatus, c.reason(), actorKind, actorId, occurred, c.source().system());
      recordDecisionIfAny(r, c.regulatorId(), c.reason(), occurred, c.source().system());
      markDecided(r, occurred);
      if (next == RegulationStatus.RETURNED) {
        ensureIssue(
            r,
            RegulationIssueKind.OTHER,
            "Devolvida à origem" + (c.reason() == null ? "" : ": " + c.reason()),
            "connector",
            c.source().system());
      }
      String requestAction = requestActionFor(r.status);
      if (!"updated".equals(requestAction)) {
        eventId =
            events.publishRequest(requestAction, r, c.source(), c.occurredAt(), c.reason(), null);
      }
      eventId =
          events.publishStatusChanged(
              r,
              previousStatus,
              actorKind,
              actorId,
              c.source(),
              c.occurredAt(),
              c.reason(),
              c.returnToOrigin(),
              false,
              eventId);
    } else {
      eventId = events.publishRequest("updated", r, c.source(), c.occurredAt(), c.reason(), null);
    }
    detectIssues(r);
    afterStatus(r, previousStatus, null, eventId);
    return toDto(r, true);
  }

  /** Efeitos derivados do status: falta → tarefa de recuperação (AGE-006); encerramento. */
  private void afterStatus(
      RegulationRequest r, String previousStatus, CitizenDetail citizen, String causationId) {
    RegulationStatus status = RegulationStatus.fromWire(r.status);
    if (status == RegulationStatus.NO_SHOW
        && !RegulationStatus.NO_SHOW.wire().equals(previousStatus)) {
      openNoShowRecovery(r, citizen, causationId);
    }
    if (status.isTerminal()) {
      for (RegulationIssue i : issues.openByRequest(r.id)) {
        resolve(i, "pedido encerrado (" + r.status + ")");
      }
    }
  }

  private void openNoShowRecovery(RegulationRequest r, CitizenDetail citizen, String cause) {
    if (taskQueries.findOpenByOrigin("rule", r.id).isPresent()) {
      return;
    }
    CitizenDetail c = citizen != null ? citizen : safeCitizen(r.citizenId);
    Assignee assignee;
    if (c != null && c.teamIne() != null && !c.teamIne().isBlank()) {
      assignee = Assignee.team(c.teamIne());
    } else if (c != null && Cnes.isValid(c.healthUnitCnes())) {
      assignee = Assignee.healthUnit(c.healthUnitCnes());
    } else if (Cnes.isValid(r.requestingCnes)) {
      assignee = Assignee.healthUnit(r.requestingCnes);
    } else {
      assignee = Assignee.queue("busca_ativa");
    }
    taskCommands.create(
        new TaskCreate(
            TaskType.NO_SHOW_RECOVERY,
            TaskPriority.HIGH,
            "Falta em vaga regulada — contatar e reagendar",
            "Cidadão faltou à vaga regulada "
                + r.id
                + " ("
                + r.requestedServiceCode
                + "). Realizar busca ativa e solicitar novo agendamento ao regulador.",
            r.citizenId,
            assignee,
            null,
            null,
            new TaskOrigin("rule", r.id, NO_SHOW_RULE_VERSION)),
        cause);
  }

  // ---------------------------------------------------------------------
  // pendências (REG-005)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public RegulationRequestDto addIssue(String requestId, RegulationIssueCreate create) {
    RegulationRequest r = load(requestId);
    String originKind = create.origin() == null ? null : create.origin().kind();
    boolean actorIsAgent = currentActor.clientType() == CurrentActor.ClientType.AGENT;
    if ("agent".equals(originKind) && !currentActor.hasRole(Roles.AGENTE_IA)) {
      throw new ProblemException(
          403,
          "Acesso negado",
          "origem 'agent' exige o papel agente_ia (aprovação humana registrada no ai-service)",
          "urn:sus-nexus:problem:forbidden");
    }
    if (actorIsAgent && !"agent".equals(originKind)) {
      throw DomainValidationException.field(
          "origin.kind", "agente de IA deve declarar origem 'agent'");
    }
    if (originKind == null) {
      originKind = "user";
    }
    RegulationIssue issue = new RegulationIssue();
    issue.id = Ulid.generate(Ulid.REGULATION_ISSUE);
    issue.tenantId = r.tenantId;
    issue.requestId = r.id;
    issue.kind = create.kind().wire();
    issue.status = "open";
    issue.description = create.description().trim();
    issue.originKind = originKind;
    issue.originId = create.origin() == null ? currentActor.actorId() : create.origin().id();
    issue.originVersion = create.origin() == null ? null : create.origin().version();
    issue.createdBy = currentActor.actorId();
    issues.persist(issue);
    r.updatedAt = Instant.now();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("issue_id", issue.id);
    details.put("kind", issue.kind);
    details.put("origin_kind", originKind);
    if (issue.originId != null) {
      details.put("origin_id", issue.originId);
    }
    audit.record(
        AuditEntry.of("regulation.issue.added", "regulation_request", r.id, r.citizenId)
            .withDetails(details));
    events.publishRequest("updated", r, null, null, "pendência: " + issue.kind, null);
    return toDto(r, true);
  }

  /** Reavalia as pendências automáticas (origem {@code rule}) do pedido. */
  private void detectIssues(RegulationRequest r) {
    RegulationStatus status = RegulationStatus.fromWire(r.status);
    boolean open = status.isOpen();
    boolean decided = status.isDecided();

    boolean noJustification = !decided && Boolean.FALSE.equals(r.justificationPresent);
    toggle(
        r,
        RegulationIssueKind.CLINICAL_JUSTIFICATION,
        noJustification,
        "Pedido sem justificativa clínica no sistema de origem");

    boolean noDocuments =
        !decided
            && r.attachedDocumentsCount != null
            && r.attachedDocumentsCount == 0
            && requiredKinds().contains(r.kind);
    toggle(
        r,
        RegulationIssueKind.MISSING_DOCUMENT,
        noDocuments,
        "Pedido sem documentos anexos (tipo " + r.kind + ")");

    boolean duplicate =
        open && !requests.openDuplicates(r.citizenId, r.requestedServiceCode, r.id).isEmpty();
    toggle(
        r,
        RegulationIssueKind.DUPLICATE,
        duplicate,
        "Outro pedido aberto do mesmo cidadão para o serviço " + r.requestedServiceCode);

    boolean noCapacity =
        open
            && capacity.knowsService(r.requestedServiceCode)
            && !capacity.hasAvailable(r.requestedServiceCode, competenceOf(r.requestedAt));
    toggle(
        r,
        RegulationIssueKind.NO_CAPACITY,
        noCapacity,
        "Sem vaga disponível para o serviço " + r.requestedServiceCode);

    if (!decided && r.slaDueAt != null && r.slaDueAt.isBefore(Instant.now()) && !r.slaBreached) {
      r.slaBreached = true;
      ensureIssue(
          r,
          RegulationIssueKind.SLA_BREACHED,
          "SLA de decisão vencido em " + r.slaDueAt,
          "rule",
          ISSUE_RULE);
    }
    if (decided && !status.isTerminal()) {
      for (RegulationIssue i : issues.openByRequest(r.id)) {
        if ("rule".equals(i.originKind)
            && !RegulationIssueKind.SLA_BREACHED.wire().equals(i.kind)) {
          resolve(i, "decisão registrada (" + r.status + ")");
        }
      }
    }
  }

  private void toggle(RegulationRequest r, RegulationIssueKind kind, boolean active, String desc) {
    Optional<RegulationIssue> existing = issues.openByKind(r.id, kind.wire());
    if (active && existing.isEmpty()) {
      ensureIssue(r, kind, desc, "rule", ISSUE_RULE);
    } else if (!active && existing.isPresent() && "rule".equals(existing.get().originKind)) {
      resolve(existing.get(), "condição não se aplica mais");
    }
  }

  private RegulationIssue ensureIssue(
      RegulationRequest r,
      RegulationIssueKind kind,
      String desc,
      String originKind,
      String originId) {
    Optional<RegulationIssue> existing = issues.openByKind(r.id, kind.wire());
    if (existing.isPresent()) {
      return existing.get();
    }
    RegulationIssue i = new RegulationIssue();
    i.id = Ulid.generate(Ulid.REGULATION_ISSUE);
    i.tenantId = r.tenantId;
    i.requestId = r.id;
    i.kind = kind.wire();
    i.status = "open";
    i.description = desc;
    i.originKind = originKind;
    i.originId = originId;
    i.originVersion = "rule".equals(originKind) ? ISSUE_RULE_VERSION : null;
    i.createdBy = currentActor.actorId();
    issues.persist(i);
    LOG.debugf("REG-005 pendência %s em %s", i.kind, r.id);
    return i;
  }

  private static void resolve(RegulationIssue i, String resolution) {
    i.status = "resolved";
    i.resolvedAt = Instant.now();
    i.resolution = resolution;
  }

  private Set<String> requiredKinds() {
    return Set.copyOf(
        Arrays.stream(documentsRequiredKinds.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList());
  }

  // ---------------------------------------------------------------------
  // SLA (REG-010) — usado pelo RegulationSlaWorkflow
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public Optional<RegulationRequestDto> breachSla(String requestId) {
    RegulationRequest r = requests.findById(requestId);
    if (r == null || RegulationStatus.fromWire(r.status).isDecided()) {
      return Optional.empty();
    }
    if (r.slaBreached && taskQueries.findOpenByOrigin("workflow", slaWorkflowId(r)).isPresent()) {
      return Optional.of(toDto(r, true)); // idempotente
    }
    r.slaBreached = true;
    r.updatedAt = Instant.now();
    ensureIssue(
        r,
        RegulationIssueKind.SLA_BREACHED,
        "SLA de decisão vencido em " + r.slaDueAt,
        "workflow",
        slaWorkflowId(r));
    String eventId =
        events.publishStatusChanged(
            r,
            r.status,
            "workflow",
            slaWorkflowId(r),
            null,
            null,
            "SLA de regulação vencido",
            null,
            true,
            null);
    if (taskQueries.findOpenByOrigin("workflow", slaWorkflowId(r)).isEmpty()) {
      taskCommands.create(
          new TaskCreate(
              TaskType.GENERIC,
              taskPriorityFor(r.priority),
              "SLA de regulação vencido",
              "Solicitação "
                  + r.id
                  + " ("
                  + r.requestedServiceCode
                  + ", prioridade "
                  + r.priority
                  + ") sem decisão após o prazo de SLA. Verificar no sistema de regulação.",
              r.citizenId,
              Assignee.queue(SLA_QUEUE),
              null,
              null,
              TaskOrigin.workflow(slaWorkflowId(r), "1.0")),
          eventId);
    }
    return Optional.of(toDto(r, true));
  }

  @Override
  @TenantTransactional
  public Optional<String> halfSlaReached(String requestId) {
    RegulationRequest r = requests.findById(requestId);
    if (r == null || RegulationStatus.fromWire(r.status).isDecided()) {
      return Optional.empty();
    }
    boolean incomplete =
        issues.openByRequest(r.id).stream()
            .anyMatch(i -> RegulationIssueKind.fromWire(i.kind).isIncomplete());
    if (!incomplete) {
      return Optional.empty();
    }
    String originId = slaWorkflowId(r) + ":half";
    Optional<TaskDto> existing = taskQueries.findOpenByOrigin("workflow", originId);
    if (existing.isPresent()) {
      return existing.map(TaskDto::id);
    }
    Assignee assignee =
        Cnes.isValid(r.requestingCnes)
            ? Assignee.healthUnit(r.requestingCnes)
            : Assignee.queue(SLA_QUEUE);
    TaskDto task =
        taskCommands.create(
            new TaskCreate(
                TaskType.REGULATION_PENDING_DOCUMENT,
                taskPriorityFor(r.priority),
                "Pendência documental em solicitação regulatória",
                "Solicitação "
                    + r.id
                    + " ("
                    + r.requestedServiceCode
                    + ") atingiu metade do SLA com pendência documental/administrativa aberta."
                    + " Complementar no sistema de regulação.",
                r.citizenId,
                assignee,
                null,
                null,
                TaskOrigin.workflow(originId, "1.0")),
            null);
    return Optional.of(task.id());
  }

  static String slaWorkflowId(RegulationRequest r) {
    return "regulation-sla:" + r.id;
  }

  static TaskPriority taskPriorityFor(String priority) {
    return switch (RegulationPriority.fromWire(priority)) {
      case EMERGENCY, URGENT -> TaskPriority.URGENT;
      case PRIORITY -> TaskPriority.HIGH;
      case ELECTIVE -> TaskPriority.MEDIUM;
    };
  }

  /** Calcula/recalcula o prazo de decisão a partir da política vigente por prioridade. */
  private void applySla(RegulationRequest r, boolean created) {
    if (!created && RegulationStatus.fromWire(r.status).isDecided()) {
      return;
    }
    Optional<RegulationSlaPolicyRepository.Policy> policy =
        slaPolicies.resolve(RegulationPriority.fromWire(r.priority));
    if (policy.isEmpty()) {
      return;
    }
    if (created || !Objects.equals(r.slaPolicyId, policy.get().id())) {
      r.slaPolicyId = policy.get().id();
      r.slaDueAt = r.requestedAt.plus(policy.get().dueIn());
      if (r.slaBreached && r.slaDueAt.isAfter(Instant.now())) {
        r.slaBreached = false;
      }
    }
  }

  // ---------------------------------------------------------------------
  // consultas
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public RegulationRequestDto get(String requestId) {
    return toDto(load(requestId), true);
  }

  @Override
  @TenantTransactional
  public Optional<RegulationRequestDto> findBySourceRecord(
      String sourceSystem, String sourceRecordId) {
    if (sourceRecordId == null || sourceRecordId.isBlank()) {
      return Optional.empty();
    }
    String tenant = tenantContext.require();
    Optional<RegulationSourceLink> link =
        sourceSystem == null
            ? Optional.empty()
            : links.findBySource(tenant, sourceSystem, sourceRecordId.trim());
    if (link.isEmpty()) {
      link = links.findAnySystem(tenant, sourceRecordId.trim());
    }
    return link.map(l -> requests.findById(l.requestId))
        .filter(Objects::nonNull)
        .map(r -> toDto(r, false));
  }

  @Override
  @TenantTransactional
  public Page<RegulationRequestDto> list(RegulationQuery q) {
    int size = Cursor.limit(q.limit());
    if (q.issue() != null
        && !q.issue().isBlank()
        && !Set.of("incomplete", "returned", "expired", "duplicate", "no_capacity", "sla_breached")
            .contains(q.issue().trim())) {
      throw DomainValidationException.field("issue", "valor desconhecido");
    }
    if (q.sort() != null
        && !q.sort().isBlank()
        && !Set.of("waiting_time_desc", "priority_desc", "created_at_asc")
            .contains(q.sort().trim())) {
      throw DomainValidationException.field("sort", "valor desconhecido");
    }
    int offset = offsetOf(q.cursor());
    List<RegulationRequest> rows = requests.list(q, offset, size + 1);
    List<RegulationRequestDto> items = rows.stream().limit(size).map(r -> toDto(r, false)).toList();
    String next = rows.size() > size ? Cursor.encode("offset:" + (offset + size)) : null;
    return new Page<>(items, next);
  }

  private static int offsetOf(String cursor) {
    String decoded = Cursor.decode(cursor).orElse(null);
    if (decoded == null) {
      return 0;
    }
    try {
      return Integer.parseInt(decoded.replace("offset:", ""));
    } catch (NumberFormatException e) {
      throw DomainValidationException.field("cursor", "cursor inválido");
    }
  }

  @Override
  @TenantTransactional
  public long countOpen(String citizenId) {
    return requests.countOpen(citizenId);
  }

  @Override
  @TenantTransactional
  public List<RegulationQueueItemDto> queueSummary(String groupBy) {
    String g = groupBy == null || groupBy.isBlank() ? "specialty" : groupBy.trim();
    if (!RegulationQueueRepository.GROUP_BY.contains(g)) {
      throw DomainValidationException.field("group_by", "valor desconhecido");
    }
    return queue.summary(g);
  }

  // ---------------------------------------------------------------------
  // capacidade (REG-006)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public Page<ProviderCapacityDto> listCapacity(
      String providerCnes, String serviceCode, String competence, String cursor, Integer limit) {
    int size = Cursor.limit(limit);
    int offset = offsetOf(cursor);
    List<ProviderCapacity> rows =
        capacity.list(blank(providerCnes), blank(serviceCode), blank(competence), offset, size + 1);
    List<ProviderCapacityDto> items = rows.stream().limit(size).map(this::toDto).toList();
    String next = rows.size() > size ? Cursor.encode("offset:" + (offset + size)) : null;
    return new Page<>(items, next);
  }

  @Override
  @TenantTransactional
  public UpsertResult upsertCapacity(ProviderCapacityUpsert upsert) {
    String tenant = tenantContext.require();
    UpsertResult.Counter counter = new UpsertResult.Counter();
    Instant now = Instant.now();
    Set<String> touchedServices = new java.util.LinkedHashSet<>();
    for (ProviderCapacityDto item : upsert.items()) {
      if (!Cnes.isValid(item.providerCnes())
          || !Competence.isValid(item.competence())
          || item.serviceCode() == null
          || item.serviceCode().isBlank()
          || item.offered() == null
          || item.offered() < 0
          || (item.used() != null && item.used() < 0)) {
        counter.rejected();
        continue;
      }
      int used = item.used() == null ? 0 : item.used();
      int available = item.available() != null ? item.available() : item.offered() - used;
      String codeSystem = item.codeSystem() == null ? "SIGTAP" : item.codeSystem().toUpperCase();
      Optional<ProviderCapacity> existing =
          capacity.findByKey(
              tenant, item.providerCnes(), item.serviceCode().trim(), item.competence());
      if (existing.isEmpty()) {
        ProviderCapacity c = new ProviderCapacity();
        c.id = Ulid.generate(Ulid.PROVIDER_CAPACITY);
        c.tenantId = tenant;
        c.providerCnes = item.providerCnes();
        c.serviceCode = item.serviceCode().trim();
        c.codeSystem = codeSystem;
        c.competence = item.competence();
        c.offered = item.offered();
        c.used = used;
        c.available = available;
        c.sourceSystem = blank(item.sourceSystem());
        c.createdAt = now;
        c.updatedAt = now;
        capacity.persist(c);
        counter.created();
      } else {
        ProviderCapacity c = existing.get();
        boolean changed = c.offered != item.offered() || c.used != used || c.available != available;
        changed |= !Objects.equals(c.codeSystem, codeSystem);
        if (changed) {
          c.offered = item.offered();
          c.used = used;
          c.available = available;
          c.codeSystem = codeSystem;
          c.sourceSystem = blank(item.sourceSystem());
          c.updatedAt = now;
          counter.updated();
        } else {
          counter.unchanged();
        }
      }
      touchedServices.add(item.serviceCode().trim());
    }
    // reavalia a pendência no_capacity dos pedidos abertos dos serviços afetados
    for (String service : touchedServices) {
      for (RegulationRequest r :
          requests.list(
              new RegulationQuery(
                  null,
                  null,
                  null,
                  service,
                  null,
                  null,
                  null,
                  null,
                  null,
                  "created_at_asc",
                  null,
                  200),
              0,
              200)) {
        if (RegulationStatus.fromWire(r.status).isOpen()) {
          detectIssues(r);
        }
      }
    }
    return counter.result();
  }

  // ---------------------------------------------------------------------

  private void rejectAgents() {
    if (currentActor.clientType() == CurrentActor.ClientType.AGENT
        || currentActor.hasRole(Roles.AGENTE_IA)) {
      throw new ProblemException(
          403,
          "Acesso negado",
          "agentes de IA não alteram status regulatório (REG-009)",
          "urn:sus-nexus:problem:forbidden");
    }
  }

  /** actor_kind do evento (nunca {@code agent}). */
  private String actorKind(String regulatorId) {
    if (regulatorId != null && !regulatorId.isBlank()) {
      return "regulator";
    }
    if (currentActor.hasRole(Roles.REGULADOR)) {
      return "regulator";
    }
    if (currentActor.hasRole(Roles.OPERADOR_INTEGRACAO)
        || currentActor.clientType() == CurrentActor.ClientType.SERVICE) {
      return "connector";
    }
    if (currentActor.hasRole(Roles.PROFISSIONAL_APS) || currentActor.hasRole(Roles.AGENDADOR)) {
      return "requester";
    }
    return "system";
  }

  private CitizenDetail resolveCitizen(RegulationRequestRegistration.CitizenRef ref) {
    if (ref.municipalCitizenId() != null && !ref.municipalCitizenId().isBlank()) {
      CitizenDetail c;
      try {
        c = citizens.get(ref.municipalCitizenId().trim());
      } catch (NotFoundException e) {
        throw DomainValidationException.field(
            "citizen_ref.municipal_citizen_id", "cidadão não encontrado");
      }
      int hops = 0;
      while (c.mergedIntoId() != null && hops++ < 10) {
        c = citizens.get(c.mergedIntoId());
      }
      return c;
    }
    if (ref.identifierSystem() == null
        || ref.identifierValue() == null
        || ref.identifierValue().isBlank()) {
      throw DomainValidationException.field(
          "citizen_ref", "informe municipal_citizen_id ou identifier_system + identifier_value");
    }
    Page<CitizenSummary> found =
        citizens.search(
            null, ref.identifierSystem().name() + "|" + ref.identifierValue(), null, null, null, 1);
    if (found.items().isEmpty()) {
      throw DomainValidationException.field(
          "citizen_ref", "cidadão não localizado pelo identificador informado");
    }
    return resolveCitizen(
        new RegulationRequestRegistration.CitizenRef(found.items().get(0).id(), null, null));
  }

  private CitizenDetail safeCitizen(String citizenId) {
    try {
      return citizens.get(citizenId);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void validate(RegulationRequestRegistration reg) {
    validateCnes("requesting_cnes", reg.requestingCnes());
    validateCnes("provider_cnes", reg.providerCnes());
    if (reg.codeSystem() != null && !CODE_SYSTEMS.contains(reg.codeSystem().toUpperCase())) {
      throw DomainValidationException.field("code_system", "valores: SIGTAP|LOCAL");
    }
  }

  private static void validateCnes(String field, String value) {
    if (value != null && !value.isBlank() && !Cnes.isValid(value)) {
      throw DomainValidationException.field(field, "CNES deve ter 7 dígitos");
    }
  }

  /** Copia os campos do registro para a entidade; retorna se algo mudou. CID nunca é persistido. */
  private static boolean copy(RegulationRequestRegistration reg, RegulationRequest r) {
    boolean changed = false;
    changed |= set(r.kind, reg.kind().wire(), v -> r.kind = v);
    changed |= set(r.status, reg.status().wire(), v -> r.status = v);
    changed |=
        set(
            r.priority,
            (reg.priority() == null ? RegulationPriority.ELECTIVE : reg.priority()).wire(),
            v -> r.priority = v);
    changed |= set(r.requestedAt, micros(reg.requestedAt()), v -> r.requestedAt = v);
    changed |=
        set(
            r.requestedServiceCode,
            reg.requestedServiceCode().trim(),
            v -> r.requestedServiceCode = v);
    changed |=
        set(
            r.codeSystem,
            reg.codeSystem() == null ? "SIGTAP" : reg.codeSystem().toUpperCase(),
            v -> r.codeSystem = v);
    changed |= set(r.specialty, blank(reg.specialty()), v -> r.specialty = v);
    changed |= set(r.requestingCnes, blank(reg.requestingCnes()), v -> r.requestingCnes = v);
    changed |=
        set(
            r.requestingProfessionalId,
            blank(reg.requestingProfessionalId()),
            v -> r.requestingProfessionalId = v);
    changed |=
        set(
            r.requestingProfessionalCbo,
            blank(reg.requestingProfessionalCbo()),
            v -> r.requestingProfessionalCbo = v);
    changed |=
        set(r.justificationPresent, reg.justificationPresent(), v -> r.justificationPresent = v);
    changed |=
        set(
            r.attachedDocumentsCount,
            reg.attachedDocumentsCount(),
            v -> r.attachedDocumentsCount = v);
    changed |= set(r.providerCnes, blank(reg.providerCnes()), v -> r.providerCnes = v);
    changed |= set(r.scheduledAt, micros(reg.scheduledAt()), v -> r.scheduledAt = v);
    changed |= set(r.regulatorId, blank(reg.regulatorId()), v -> r.regulatorId = v);
    changed |= set(r.decisionReason, blank(reg.decisionReason()), v -> r.decisionReason = v);
    return changed;
  }

  private static Instant micros(OffsetDateTime t) {
    return t == null ? null : t.toInstant().truncatedTo(ChronoUnit.MICROS);
  }

  private static <T> boolean set(T current, T value, Consumer<T> setter) {
    if (Objects.equals(current, value)) {
      return false;
    }
    setter.accept(value);
    return true;
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  static String competenceOf(Instant at) {
    return Competence.of(YearMonth.from(at.atOffset(ZoneOffset.UTC))).value();
  }

  private void recordHistory(
      RegulationRequest r,
      String previous,
      String reason,
      String actorKind,
      String actorId,
      Instant occurredAt,
      String sourceSystem) {
    RegulationStatusHistory h = new RegulationStatusHistory();
    h.id = Ulid.generate(Ulid.REGULATION_HISTORY);
    h.tenantId = r.tenantId;
    h.requestId = r.id;
    h.status = r.status;
    h.previousStatus = previous;
    h.reason = reason;
    h.actorKind = actorKind;
    h.actorId = actorId != null ? actorId : currentActor.actorId();
    h.occurredAt = occurredAt;
    h.sourceSystem = sourceSystem;
    history.persist(h);
  }

  /** Decisão (autorizada/negada/devolvida) registrada a partir do sistema oficial (REG-003). */
  private void recordDecisionIfAny(
      RegulationRequest r, String regulatorId, String reason, Instant occurredAt, String source) {
    RegulationStatus s = RegulationStatus.fromWire(r.status);
    if (s != RegulationStatus.AUTHORIZED
        && s != RegulationStatus.DENIED
        && s != RegulationStatus.RETURNED) {
      return;
    }
    RegulationDecision d = new RegulationDecision();
    d.id = Ulid.generate(Ulid.REGULATION_DECISION);
    d.tenantId = r.tenantId;
    d.requestId = r.id;
    d.regulatorId = regulatorId != null ? regulatorId : r.regulatorId;
    d.decision = r.status;
    d.reason = reason;
    d.occurredAt = occurredAt;
    d.sourceSystem = source;
    decisions.persist(d);
  }

  private static void markDecided(RegulationRequest r, Instant at) {
    if (r.decidedAt == null && RegulationStatus.fromWire(r.status).isDecided()) {
      r.decidedAt = at;
    }
  }

  static String requestActionFor(String status) {
    return switch (RegulationStatus.fromWire(status)) {
      case RETURNED -> "returned";
      case CANCELLED -> "cancelled";
      default -> "updated";
    };
  }

  private RegulationRequest load(String id) {
    RegulationRequest r = requests.findById(id);
    if (r == null) {
      throw new NotFoundException("solicitação regulatória", id);
    }
    return r;
  }

  RegulationRequestDto toDto(RegulationRequest r, boolean full) {
    RegulationStatus status = RegulationStatus.fromWire(r.status);
    Instant end =
        status.isOpen() ? Instant.now() : (r.decidedAt != null ? r.decidedAt : r.updatedAt);
    long waitingDays = Math.max(0, Duration.between(r.requestedAt, end).toDays());
    List<RegulationIssueDto> issueDtos =
        issues.byRequest(r.id).stream()
            .map(
                i ->
                    new RegulationIssueDto(
                        i.id,
                        RegulationIssueKind.fromWire(i.kind),
                        i.status,
                        i.description,
                        i.originKind == null
                            ? null
                            : new RegulationIssueDto.Origin(
                                i.originKind, i.originId, i.originVersion),
                        offset(i.createdAt),
                        offset(i.resolvedAt)))
            .toList();
    List<RegulationRequestDto.StatusEntry> entries = null;
    if (full) {
      entries =
          history.byRequest(r.id).stream()
              .map(
                  h ->
                      new RegulationRequestDto.StatusEntry(
                          RegulationStatus.fromWire(h.status),
                          offset(h.occurredAt),
                          h.actorId,
                          h.reason))
              .toList();
    }
    return new RegulationRequestDto(
        r.id,
        r.citizenId,
        RegulationKind.fromWire(r.kind),
        status,
        RegulationPriority.fromWire(r.priority),
        offset(r.requestedAt),
        r.requestedServiceCode,
        r.codeSystem,
        null,
        r.specialty,
        r.requestingCnes,
        full ? unitName(r.requestingCnes) : null,
        r.requestingProfessionalId,
        r.providerCnes,
        full ? unitName(r.providerCnes) : null,
        offset(r.scheduledAt),
        r.appointmentId,
        r.regulatorId,
        r.decisionReason,
        r.justificationPresent,
        r.attachedDocumentsCount,
        waitingDays,
        offset(r.slaDueAt),
        r.slaBreached,
        issueDtos,
        entries,
        r.sourceSystem,
        r.sourceRecordId,
        r.version);
  }

  private String unitName(String cnes) {
    if (!Cnes.isValid(cnes)) {
      return null;
    }
    return healthUnits.findByCnes(cnes).map(HealthUnitDto::name).orElse(null);
  }

  private ProviderCapacityDto toDto(ProviderCapacity c) {
    return new ProviderCapacityDto(
        c.providerCnes,
        unitName(c.providerCnes),
        c.serviceCode,
        c.codeSystem,
        c.competence,
        c.offered,
        c.used,
        c.available,
        c.sourceSystem,
        offset(c.updatedAt));
  }

  private static OffsetDateTime offset(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
