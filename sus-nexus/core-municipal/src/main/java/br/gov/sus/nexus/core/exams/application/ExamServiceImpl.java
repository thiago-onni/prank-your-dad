package br.gov.sus.nexus.core.exams.application;

import br.gov.sus.nexus.core.audit.api.AccessLogService;
import br.gov.sus.nexus.core.audit.api.AccessRecord;
import br.gov.sus.nexus.core.exams.api.ExamDocumentLink;
import br.gov.sus.nexus.core.exams.api.ExamIssue;
import br.gov.sus.nexus.core.exams.api.ExamOrderDto;
import br.gov.sus.nexus.core.exams.api.ExamOrderRegistration;
import br.gov.sus.nexus.core.exams.api.ExamOrderResult;
import br.gov.sus.nexus.core.exams.api.ExamOrderStatus;
import br.gov.sus.nexus.core.exams.api.ExamResultDto;
import br.gov.sus.nexus.core.exams.api.ExamResultRegistration;
import br.gov.sus.nexus.core.exams.api.ExamService;
import br.gov.sus.nexus.core.exams.api.ExamStatusChange;
import br.gov.sus.nexus.core.exams.domain.ExamOrder;
import br.gov.sus.nexus.core.exams.domain.ExamOrderSourceLink;
import br.gov.sus.nexus.core.exams.domain.ExamOrderStatusHistory;
import br.gov.sus.nexus.core.exams.domain.ExamResult;
import br.gov.sus.nexus.core.exams.infrastructure.ExamRepositories;
import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.CitizenService;
import br.gov.sus.nexus.core.identity.api.CitizenSummary;
import br.gov.sus.nexus.core.platform.correlation.CorrelationId;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.security.Purpose;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.reference.api.HealthUnitDto;
import br.gov.sus.nexus.core.reference.api.HealthUnitService;
import br.gov.sus.nexus.core.regulation.api.RegulationRequestDto;
import br.gov.sus.nexus.core.regulation.api.RegulationService;
import br.gov.sus.nexus.core.scheduling.api.AppointmentDto;
import br.gov.sus.nexus.core.scheduling.api.AppointmentService;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
import br.gov.sus.nexus.core.tasks.api.Assignee;
import br.gov.sus.nexus.core.tasks.api.TaskCommands;
import br.gov.sus.nexus.core.tasks.api.TaskCreate;
import br.gov.sus.nexus.core.tasks.api.TaskDto;
import br.gov.sus.nexus.core.tasks.api.TaskOrigin;
import br.gov.sus.nexus.core.tasks.api.TaskPriority;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import br.gov.sus.nexus.core.tasks.api.TaskStatus;
import br.gov.sus.nexus.core.tasks.api.TaskType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Pedidos de exame: registro por vínculo de origem, histórico, resultados (metadados + referência
 * segura), pendências (EXA-004/005/009), tempos de ciclo (EXA-010), eventos e tarefas derivadas.
 */
@ApplicationScoped
public class ExamServiceImpl implements ExamService {

  private static final Logger LOG = Logger.getLogger(ExamServiceImpl.class);

  public static final String FOLLOWUP_ORIGIN_PREFIX = "exam-followup:";
  public static final String NO_SHOW_RULE = "exam.no_show";
  static final String RULE_VERSION = "1.0";
  static final Set<String> CODE_SYSTEMS = Set.of("SIGTAP", "LOINC", "LOCAL");

  @Inject ExamRepositories.Orders orders;
  @Inject ExamRepositories.History history;
  @Inject ExamRepositories.Results results;
  @Inject ExamRepositories.SourceLinks links;
  @Inject ExamEvents events;
  @Inject CitizenService citizens;
  @Inject RegulationService regulation;
  @Inject AppointmentService appointments;
  @Inject HealthUnitService healthUnits;
  @Inject TaskCommands taskCommands;
  @Inject TaskQueries taskQueries;
  @Inject AccessLogService accessLog;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;
  @Inject CorrelationId correlationId;

  @ConfigProperty(name = "sus.exams.not-scheduled-days", defaultValue = "15")
  int notScheduledDays;

  @ConfigProperty(name = "sus.exams.result-pending-days", defaultValue = "7")
  int resultPendingDays;

  @ConfigProperty(name = "sus.exams.followup-days", defaultValue = "10")
  int followupDays;

  @ConfigProperty(
      name = "sus.exams.document-base-url",
      defaultValue = "http://localhost:9000/exam-documents")
  String documentBaseUrl;

  @ConfigProperty(
      name = "sus.exams.document-signing-key",
      defaultValue = "change-me-exam-document-key")
  String documentSigningKey;

  @ConfigProperty(name = "sus.exams.document-link-ttl", defaultValue = "PT5M")
  Duration documentLinkTtl;

  // ---------------------------------------------------------------------
  // registro
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ExamOrderResult register(ExamOrderRegistration reg) {
    validate(reg);
    String tenant = tenantContext.require();
    CitizenDetail citizen = resolveCitizen(reg.citizenRef());
    Instant now = Instant.now();
    OffsetDateTime occurredAt =
        reg.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : reg.occurredAt();

    Optional<ExamOrderSourceLink> link =
        links.findBySource(tenant, reg.source().system(), reg.source().sourceRecordId());
    if (link.isEmpty()) {
      ExamOrder o = new ExamOrder();
      o.id = Ulid.generate(Ulid.EXAM_ORDER);
      o.tenantId = tenant;
      o.citizenId = citizen.id();
      o.sourceSystem = reg.source().system();
      o.sourceRecordId = reg.source().sourceRecordId();
      o.sourceRecordVersion = reg.source().sourceRecordVersion();
      copy(reg, o);
      linkRegulationAndAppointment(
          o,
          reg.source().system(),
          reg.regulationSourceRecordId(),
          reg.appointmentSourceRecordId());
      applyTimestamps(o, ExamOrderStatus.fromWire(o.status), occurredAt.toInstant(), null);
      o.createdAt = now;
      o.updatedAt = now;
      recomputeIssues(o);
      orders.persist(o);

      ExamOrderSourceLink l = new ExamOrderSourceLink();
      l.id = Ulid.generate(Ulid.SOURCE_LINK);
      l.tenantId = tenant;
      l.orderId = o.id;
      l.sourceSystem = reg.source().system();
      l.connector = reg.source().connector();
      l.sourceRecordId = reg.source().sourceRecordId();
      l.sourceRecordVersion = reg.source().sourceRecordVersion();
      links.persist(l);

      recordHistory(o, null, null, occurredAt.toInstant(), reg.source().system());
      events.publishOrder("created", o, null, reg.source(), occurredAt, null, null);
      return new ExamOrderResult(toDto(o, true), true);
    }

    ExamOrder o = load(link.get().orderId);
    String previousStatus = o.status;
    boolean changed = copy(reg, o);
    changed |=
        linkRegulationAndAppointment(
            o,
            reg.source().system(),
            reg.regulationSourceRecordId(),
            reg.appointmentSourceRecordId());
    if (!Objects.equals(o.citizenId, citizen.id())) {
      o.citizenId = citizen.id();
      changed = true;
    }
    if (!changed) {
      recomputeIssues(o);
      return new ExamOrderResult(toDto(o, true), false);
    }
    o.sourceRecordVersion = reg.source().sourceRecordVersion();
    o.updatedAt = now;
    link.get().sourceRecordVersion = reg.source().sourceRecordVersion();
    link.get().updatedAt = now;
    if (!previousStatus.equals(o.status)) {
      applyTimestamps(o, ExamOrderStatus.fromWire(o.status), occurredAt.toInstant(), null);
      recordHistory(o, previousStatus, null, occurredAt.toInstant(), reg.source().system());
      events.publishOrder(
          "status_changed", o, previousStatus, reg.source(), occurredAt, null, null);
    }
    recomputeIssues(o);
    return new ExamOrderResult(toDto(o, true), false);
  }

  private boolean linkRegulationAndAppointment(
      ExamOrder o, String system, String regulationSourceId, String appointmentSourceId) {
    boolean changed = false;
    if (o.regulationRequestId == null
        && regulationSourceId != null
        && !regulationSourceId.isBlank()) {
      Optional<RegulationRequestDto> r = regulation.findBySourceRecord(system, regulationSourceId);
      if (r.isPresent()) {
        o.regulationRequestId = r.get().id();
        changed = true;
      }
    }
    if (o.appointmentId == null && appointmentSourceId != null && !appointmentSourceId.isBlank()) {
      Optional<AppointmentDto> a = appointments.findBySourceRecord(system, appointmentSourceId);
      if (a.isPresent()) {
        o.appointmentId = a.get().id();
        if (o.scheduledAt == null) {
          o.scheduledAt = a.get().scheduledStart().toInstant();
        }
        changed = true;
      }
    }
    return changed;
  }

  // ---------------------------------------------------------------------
  // status
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ExamOrderDto changeStatus(String orderId, ExamStatusChange c) {
    ExamOrder o = load(orderId);
    validateCnes("performer_cnes", c.performerCnes());
    Instant occurred = c.occurredAt().toInstant();
    String previousStatus = o.status;
    ExamOrderStatus next = c.status();
    boolean changed = set(o.status, next.wire(), v -> o.status = v);
    changed |= set(o.performerCnes, blank(c.performerCnes()), v -> o.performerCnes = v);
    changed |=
        linkRegulationAndAppointment(o, c.source().system(), null, c.appointmentSourceRecordId());
    Instant before = o.scheduledAt;
    applyTimestamps(o, next, occurred, c.scheduledAt() == null ? null : micros(c.scheduledAt()));
    changed |= !Objects.equals(before, o.scheduledAt);
    if (!changed) {
      return toDto(o, true);
    }
    o.updatedAt = Instant.now();
    if (!previousStatus.equals(o.status)) {
      recordHistory(o, previousStatus, c.reason(), occurred, c.source().system());
      events.publishOrder(
          "status_changed", o, previousStatus, c.source(), c.occurredAt(), c.reason(), null);
    }
    recomputeIssues(o);
    return toDto(o, true);
  }

  /** {@code scheduled} grava {@code scheduled_at}; {@code performed} grava {@code performed_at}. */
  private static void applyTimestamps(
      ExamOrder o, ExamOrderStatus status, Instant occurredAt, Instant scheduledAt) {
    switch (status) {
      case SCHEDULED ->
          o.scheduledAt =
              scheduledAt != null
                  ? scheduledAt
                  : (o.scheduledAt != null ? o.scheduledAt : occurredAt);
      case PERFORMED, COLLECTED -> {
        if (o.performedAt == null) {
          o.performedAt = occurredAt;
        }
      }
      case REPORTED -> {
        if (o.reportedAt == null) {
          o.reportedAt = occurredAt;
        }
      }
      default -> {
        if (scheduledAt != null) {
          o.scheduledAt = scheduledAt;
        }
      }
    }
  }

  // ---------------------------------------------------------------------
  // resultados (EXA-006/007/008)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ExamOrderDto registerResult(String orderId, ExamResultRegistration reg) {
    return applyResult(load(orderId), reg);
  }

  @Override
  @TenantTransactional
  public ExamOrderDto registerResultBySource(
      String sourceSystem, String sourceRecordId, ExamResultRegistration reg) {
    ExamOrderSourceLink link =
        links
            .findBySource(tenantContext.require(), sourceSystem, sourceRecordId)
            .orElseThrow(
                () ->
                    new NotFoundException("pedido de exame", sourceSystem + "/" + sourceRecordId));
    return applyResult(load(link.orderId), reg);
  }

  private ExamOrderDto applyResult(ExamOrder o, ExamResultRegistration reg) {
    validateCnes("performer_cnes", reg.performerCnes());
    Instant now = Instant.now();
    ExamResult r = new ExamResult();
    r.id = Ulid.generate(Ulid.EXAM_RESULT);
    r.tenantId = o.tenantId;
    r.orderId = o.id;
    r.reportedAt = micros(reg.reportedAt());
    r.status = reg.status();
    r.critical = Boolean.TRUE.equals(reg.critical());
    r.performerCnes = blank(reg.performerCnes());
    r.documentRef = blank(reg.documentRef());
    r.documentContentType = blank(reg.documentContentType());
    r.documentSha256 = reg.documentSha256() == null ? null : reg.documentSha256().toLowerCase();
    r.observations = sanitize(reg.observations());
    r.observationsCount = r.observations.size();
    r.sourceSystem = reg.source().system();
    r.sourceRecordId = reg.source().sourceRecordId();
    results.persist(r);

    String previousStatus = o.status;
    ExamOrderStatus current = ExamOrderStatus.fromWire(o.status);
    if (!current.isTerminal() && !"cancelled".equals(r.status)) {
      o.status = ExamOrderStatus.REPORTED.wire();
    }
    if (o.reportedAt == null || r.reportedAt.isBefore(o.reportedAt)) {
      o.reportedAt = r.reportedAt;
    }
    if (o.performedAt == null) {
      o.performedAt = r.reportedAt;
    }
    if (o.performerCnes == null) {
      o.performerCnes = r.performerCnes;
    }
    o.updatedAt = now;
    String causation = null;
    if (!previousStatus.equals(o.status)) {
      recordHistory(o, previousStatus, "laudo " + r.status, r.reportedAt, reg.source().system());
      causation =
          events.publishOrder(
              "status_changed", o, previousStatus, reg.source(), reg.reportedAt(), null, null);
    }
    String available = events.publishResult("available", o, r, reg.source(), causation);
    if (r.critical) {
      String flagged = events.publishResult("critical_flagged", o, r, reg.source(), available);
      r.followupTaskId = openCriticalFollowup(o, r, flagged);
    }
    recomputeIssues(o);
    return toDto(o, true);
  }

  /** EXA-008: tarefa urgente ao solicitante, SEM conteúdo do resultado. */
  private String openCriticalFollowup(ExamOrder o, ExamResult r, String causationId) {
    Optional<TaskDto> existing =
        taskQueries.findOpenByOrigin("rule", FOLLOWUP_ORIGIN_PREFIX + o.id);
    if (existing.isPresent()) {
      return existing.get().id();
    }
    TaskDto task =
        taskCommands.create(
            new TaskCreate(
                TaskType.EXAM_RESULT_FOLLOWUP,
                TaskPriority.URGENT,
                "Resultado crítico de exame — contato imediato",
                "Resultado crítico disponível para o pedido "
                    + o.id
                    + " (exame "
                    + o.examCode
                    + "). Acessar o laudo no sistema de origem, contatar o cidadão e registrar a"
                    + " conduta.",
                o.citizenId,
                requesterAssignee(o),
                null,
                null,
                new TaskOrigin("rule", FOLLOWUP_ORIGIN_PREFIX + o.id, "exam.critical_result/1.0")),
            causationId);
    LOG.infof("EXA-008 resultado crítico em %s: tarefa %s", o.id, task.id());
    return task.id();
  }

  private Assignee requesterAssignee(ExamOrder o) {
    if (o.requestingProfessionalId != null && !o.requestingProfessionalId.isBlank()) {
      return Assignee.user(o.requestingProfessionalId);
    }
    if (Cnes.isValid(o.requestingCnes)) {
      return Assignee.healthUnit(o.requestingCnes);
    }
    CitizenDetail c = safeCitizen(o.citizenId);
    if (c != null && c.teamIne() != null && !c.teamIne().isBlank()) {
      return Assignee.team(c.teamIne());
    }
    if (c != null && Cnes.isValid(c.healthUnitCnes())) {
      return Assignee.healthUnit(c.healthUnitCnes());
    }
    return Assignee.queue("coordenacao_aps");
  }

  /** Mantém só campos codificados; descarta qualquer texto livre (EXA-006). */
  static List<Map<String, Object>> sanitize(List<ExamResultRegistration.Observation> observations) {
    if (observations == null) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (ExamResultRegistration.Observation ob : observations) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("code", ob.code().trim());
      m.put("code_system", ob.codeSystem());
      if (ob.value() != null) {
        m.put("value", ob.value());
      }
      if (ob.unit() != null && !ob.unit().isBlank()) {
        m.put("unit", ob.unit().trim());
      }
      if (ob.abnormal() != null) {
        m.put("abnormal", ob.abnormal());
      }
      out.add(m);
    }
    return out;
  }

  // ---------------------------------------------------------------------
  // documento (EXA-006): URL assinada, curta, com access_log
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ExamDocumentLink documentLink(String orderId, String resultId) {
    ExamOrder o = load(orderId);
    ExamResult r = results.findById(resultId);
    if (r == null || !r.orderId.equals(o.id)) {
      throw new NotFoundException("resultado", resultId);
    }
    if (r.documentRef == null) {
      throw new NotFoundException("documento do resultado", resultId);
    }
    Purpose purpose = currentActor.purpose().orElse(null);
    Instant expires = Instant.now().plus(documentLinkTtl).truncatedTo(ChronoUnit.SECONDS);
    String ref = URLEncoder.encode(r.documentRef, StandardCharsets.UTF_8);
    String payload = o.id + "|" + r.id + "|" + ref + "|" + expires.getEpochSecond();
    String url =
        documentBaseUrl
            + "/"
            + ref
            + "?order="
            + o.id
            + "&result="
            + r.id
            + "&exp="
            + expires.getEpochSecond()
            + "&sig="
            + hmac(payload);
    accessLog.record(
        new AccessRecord(
            currentActor.actorId(),
            currentActor.roles(),
            "document_link",
            "exam_result",
            r.id,
            o.citizenId,
            purpose,
            true,
            currentActor.breakGlass(),
            null,
            correlationId.get()));
    return new ExamDocumentLink(url, expires.atOffset(ZoneOffset.UTC), r.documentContentType);
  }

  private String hmac(String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(documentSigningKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("falha ao assinar referência de documento", e);
    }
  }

  // ---------------------------------------------------------------------
  // pendências (EXA-004/005/009) — derivadas e persistidas em exam_order.issues
  // ---------------------------------------------------------------------

  private void recomputeIssues(ExamOrder o) {
    o.issues = computeIssues(o).toArray(String[]::new);
  }

  /**
   * Pendências derivadas das datas (prazos configuráveis) unidas às marcadas pelo workflow enquanto
   * a condição subjacente persistir (não agendado / sem laudo / sem retorno).
   */
  private Set<String> computeIssues(ExamOrder o) {
    Instant now = Instant.now();
    ExamOrderStatus status = ExamOrderStatus.fromWire(o.status);
    Set<String> issues = new LinkedHashSet<>();
    Set<String> persisted = Set.of(o.issues);
    List<ExamResult> rs = results.byOrder(o.id);
    Optional<ExamResult> latest =
        rs.isEmpty() ? Optional.empty() : Optional.of(rs.get(rs.size() - 1));
    boolean awaitingSchedule = status.isAwaitingSchedule() && o.scheduledAt == null;
    if (awaitingSchedule
        && (persisted.contains(ExamIssue.NOT_SCHEDULED.wire())
            || o.requestedAt.plus(Duration.ofDays(notScheduledDays)).isBefore(now))) {
      issues.add(ExamIssue.NOT_SCHEDULED.wire());
    }
    boolean awaitingResult = !status.isTerminal() && rs.isEmpty() && o.performedAt != null;
    if (awaitingResult
        && (persisted.contains(ExamIssue.RESULT_PENDING.wire())
            || o.performedAt.plus(Duration.ofDays(resultPendingDays)).isBefore(now))) {
      issues.add(ExamIssue.RESULT_PENDING.wire());
    }
    boolean followupDone = followupCompleted(rs);
    boolean awaitingFollowup =
        status == ExamOrderStatus.REPORTED && o.reportedAt != null && !followupDone;
    if (awaitingFollowup
        && (persisted.contains(ExamIssue.NO_RESULT_FOLLOWUP.wire())
            || o.reportedAt.plus(Duration.ofDays(followupDays)).isBefore(now))) {
      issues.add(ExamIssue.NO_RESULT_FOLLOWUP.wire());
    }
    if (persisted.contains(ExamIssue.INTEGRATION_FAILURE.wire())) {
      issues.add(ExamIssue.INTEGRATION_FAILURE.wire());
    }
    if (latest.isPresent() && "inconclusive".equals(latest.get().status)) {
      issues.add(ExamIssue.INCONCLUSIVE.wire());
    }
    if (rs.stream().anyMatch(r -> r.critical) && !followupDone) {
      issues.add(ExamIssue.CRITICAL.wire());
    }
    return issues;
  }

  private boolean followupCompleted(List<ExamResult> results) {
    return followupTask(results).map(t -> t.status() == TaskStatus.COMPLETED).orElse(false);
  }

  /** Tarefa de retorno vinculada a qualquer resultado do pedido (o mais recente com vínculo). */
  private Optional<TaskDto> followupTask(List<ExamResult> results) {
    for (int i = results.size() - 1; i >= 0; i--) {
      String id = results.get(i).followupTaskId;
      if (id != null) {
        try {
          return Optional.of(taskQueries.get(id));
        } catch (RuntimeException e) {
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }

  // ---------------------------------------------------------------------
  // suporte ao ExamFollowUpWorkflow
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public Optional<String> flagNotScheduled(String orderId) {
    ExamOrder o = orders.findById(orderId);
    if (o == null
        || !ExamOrderStatus.fromWire(o.status).isAwaitingSchedule()
        || o.scheduledAt != null) {
      return Optional.empty();
    }
    Set<String> issues = new LinkedHashSet<>(Arrays.asList(o.issues));
    issues.add(ExamIssue.NOT_SCHEDULED.wire());
    o.issues = issues.toArray(String[]::new);
    o.updatedAt = Instant.now();
    String originId = FOLLOWUP_ORIGIN_PREFIX + o.id + ":not_scheduled";
    Optional<TaskDto> existing = taskQueries.findOpenByOrigin("workflow", originId);
    if (existing.isPresent()) {
      return existing.map(TaskDto::id);
    }
    Assignee assignee =
        Cnes.isValid(o.requestingCnes)
            ? Assignee.healthUnit(o.requestingCnes)
            : Assignee.queue("regulacao");
    TaskDto task =
        taskCommands.create(
            new TaskCreate(
                TaskType.EXAM_NOT_SCHEDULED,
                priorityFor(o.priority, TaskPriority.MEDIUM),
                "Exame sem agendamento após o prazo",
                "Pedido de exame "
                    + o.id
                    + " ("
                    + o.examCode
                    + ") solicitado em "
                    + o.requestedAt.atOffset(ZoneOffset.UTC)
                    + " continua sem agendamento. Verificar vaga e contatar o cidadão.",
                o.citizenId,
                assignee,
                null,
                null,
                TaskOrigin.workflow(originId, RULE_VERSION)),
            null);
    return Optional.of(task.id());
  }

  @Override
  @TenantTransactional
  public Optional<String> openNoShowRecovery(String orderId) {
    ExamOrder o = orders.findById(orderId);
    if (o == null || ExamOrderStatus.fromWire(o.status).isTerminal()) {
      return Optional.empty();
    }
    if (o.appointmentId != null) {
      Optional<TaskDto> byAppointment = taskQueries.findOpenByOrigin("rule", o.appointmentId);
      if (byAppointment.isPresent()) {
        return byAppointment.map(TaskDto::id); // já aberta pelo módulo scheduling (AGE-006)
      }
    }
    String originId = FOLLOWUP_ORIGIN_PREFIX + o.id + ":no_show";
    Optional<TaskDto> existing = taskQueries.findOpenByOrigin("workflow", originId);
    if (existing.isPresent()) {
      return existing.map(TaskDto::id);
    }
    TaskDto task =
        taskCommands.create(
            new TaskCreate(
                TaskType.NO_SHOW_RECOVERY,
                TaskPriority.HIGH,
                "Falta em exame — contatar e reagendar",
                "Cidadão faltou ao exame "
                    + o.id
                    + " ("
                    + o.examCode
                    + "). Realizar busca ativa e reagendar.",
                o.citizenId,
                requesterAssignee(o),
                null,
                null,
                TaskOrigin.workflow(originId, RULE_VERSION)),
            null);
    return Optional.of(task.id());
  }

  @Override
  @TenantTransactional
  public boolean flagResultPending(String orderId) {
    ExamOrder o = orders.findById(orderId);
    if (o == null
        || !results.byOrder(o.id).isEmpty()
        || ExamOrderStatus.fromWire(o.status).isTerminal()) {
      return false;
    }
    Set<String> issues = new LinkedHashSet<>(Arrays.asList(o.issues));
    boolean added = issues.add(ExamIssue.RESULT_PENDING.wire());
    o.issues = issues.toArray(String[]::new);
    o.updatedAt = Instant.now();
    return added;
  }

  @Override
  @TenantTransactional
  public Optional<String> ensureResultFollowup(String orderId) {
    ExamOrder o = orders.findById(orderId);
    if (o == null || ExamOrderStatus.fromWire(o.status).isTerminal()) {
      return Optional.empty();
    }
    List<ExamResult> rs = results.byOrder(o.id);
    Optional<ExamResult> latest =
        rs.isEmpty() ? Optional.empty() : Optional.of(rs.get(rs.size() - 1));
    if (latest.isEmpty() || followupCompleted(rs)) {
      return Optional.empty();
    }
    Optional<TaskDto> open = followupTask(rs).filter(t -> !t.status().isFinal());
    if (open.isPresent()) {
      return open.map(TaskDto::id);
    }
    Optional<TaskDto> byOrigin =
        taskQueries.findOpenByOrigin("rule", FOLLOWUP_ORIGIN_PREFIX + o.id);
    if (byOrigin.isPresent()) {
      latest.get().followupTaskId = byOrigin.get().id();
      return byOrigin.map(TaskDto::id);
    }
    TaskDto task =
        taskCommands.create(
            new TaskCreate(
                TaskType.EXAM_RESULT_FOLLOWUP,
                priorityFor(o.priority, TaskPriority.MEDIUM),
                "Laudo disponível sem retorno registrado",
                "Laudo do exame "
                    + o.id
                    + " ("
                    + o.examCode
                    + ") disponível há mais de "
                    + followupDays
                    + " dias sem consulta de retorno. Agendar retorno e registrar a conduta.",
                o.citizenId,
                requesterAssignee(o),
                null,
                null,
                TaskOrigin.workflow(FOLLOWUP_ORIGIN_PREFIX + o.id, RULE_VERSION)),
            null);
    latest.get().followupTaskId = task.id();
    Set<String> issues = new LinkedHashSet<>(Arrays.asList(o.issues));
    issues.add(ExamIssue.NO_RESULT_FOLLOWUP.wire());
    o.issues = issues.toArray(String[]::new);
    o.updatedAt = Instant.now();
    return Optional.of(task.id());
  }

  static TaskPriority priorityFor(String examPriority, TaskPriority fallback) {
    if (examPriority == null) {
      return fallback;
    }
    return switch (examPriority) {
      case "urgent" -> TaskPriority.URGENT;
      case "priority" -> TaskPriority.HIGH;
      default -> fallback;
    };
  }

  // ---------------------------------------------------------------------
  // consultas
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ExamOrderDto get(String orderId) {
    return toDto(load(orderId), true);
  }

  @Override
  @TenantTransactional
  public Optional<ExamOrderDto> findByAppointment(String appointmentId) {
    return orders.findByAppointment(appointmentId).map(o -> toDto(o, false));
  }

  @Override
  @TenantTransactional
  public Optional<ExamOrderDto> findBySource(String sourceSystem, String sourceRecordId) {
    if (sourceSystem == null || sourceRecordId == null) {
      return Optional.empty();
    }
    return links
        .findBySource(tenantContext.require(), sourceSystem, sourceRecordId)
        .map(l -> orders.findById(l.orderId))
        .filter(Objects::nonNull)
        .map(o -> toDto(o, false));
  }

  @Override
  @TenantTransactional
  public Page<ExamOrderDto> list(
      String citizenId,
      ExamOrderStatus status,
      ExamIssue issue,
      String requestingCnes,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    List<ExamOrderDto> rows =
        orders
            .list(
                blank(citizenId),
                status == null ? null : status.wire(),
                issue == null ? null : issue.wire(),
                blank(requestingCnes),
                Cursor.decode(cursor).orElse(null),
                size + 1)
            .stream()
            .map(o -> toDto(o, false))
            .toList();
    return Page.of(rows, size, ExamOrderDto::id);
  }

  @Override
  @TenantTransactional
  public long countPending(String citizenId) {
    return orders.countPending(citizenId);
  }

  // ---------------------------------------------------------------------

  private CitizenDetail resolveCitizen(ExamOrderRegistration.CitizenRef ref) {
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
        new ExamOrderRegistration.CitizenRef(found.items().get(0).id(), null, null));
  }

  private CitizenDetail safeCitizen(String citizenId) {
    try {
      return citizens.get(citizenId);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void validate(ExamOrderRegistration reg) {
    validateCnes("requesting_cnes", reg.requestingCnes());
    if (reg.codeSystem() != null && !CODE_SYSTEMS.contains(reg.codeSystem().toUpperCase())) {
      throw DomainValidationException.field("code_system", "valores: SIGTAP|LOINC|LOCAL");
    }
  }

  private static void validateCnes(String field, String value) {
    if (value != null && !value.isBlank() && !Cnes.isValid(value)) {
      throw DomainValidationException.field(field, "CNES deve ter 7 dígitos");
    }
  }

  private static boolean copy(ExamOrderRegistration reg, ExamOrder o) {
    boolean changed = false;
    changed |= set(o.status, reg.status().wire(), v -> o.status = v);
    changed |= set(o.requestedAt, micros(reg.requestedAt()), v -> o.requestedAt = v);
    changed |= set(o.examCode, reg.examCode().trim(), v -> o.examCode = v);
    changed |=
        set(
            o.codeSystem,
            reg.codeSystem() == null ? "SIGTAP" : reg.codeSystem().toUpperCase(),
            v -> o.codeSystem = v);
    changed |= set(o.examDescription, blank(reg.examDescription()), v -> o.examDescription = v);
    changed |= set(o.category, blank(reg.category()), v -> o.category = v);
    changed |= set(o.priority, blank(reg.priority()), v -> o.priority = v);
    changed |= set(o.requestingCnes, blank(reg.requestingCnes()), v -> o.requestingCnes = v);
    changed |=
        set(
            o.requestingProfessionalId,
            blank(reg.requestingProfessionalId()),
            v -> o.requestingProfessionalId = v);
    changed |= set(o.careLine, blank(reg.careLine()), v -> o.careLine = v);
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

  private void recordHistory(
      ExamOrder o, String previous, String reason, Instant occurredAt, String source) {
    ExamOrderStatusHistory h = new ExamOrderStatusHistory();
    h.id = Ulid.generate(Ulid.EXAM_HISTORY);
    h.tenantId = o.tenantId;
    h.orderId = o.id;
    h.status = o.status;
    h.previousStatus = previous;
    h.reason = reason;
    h.occurredAt = occurredAt;
    h.sourceSystem = source;
    h.actorId = currentActor.actorId();
    history.persist(h);
  }

  private ExamOrder load(String id) {
    ExamOrder o = orders.findById(id);
    if (o == null) {
      throw new NotFoundException("pedido de exame", id);
    }
    return o;
  }

  ExamOrderDto toDto(ExamOrder o, boolean full) {
    List<ExamResult> rs = results.byOrder(o.id);
    List<ExamResultDto> resultDtos =
        rs.stream()
            .map(
                r ->
                    new ExamResultDto(
                        r.id,
                        offset(r.reportedAt),
                        r.status,
                        r.critical,
                        r.performerCnes,
                        r.documentRef != null,
                        r.observationsCount,
                        r.followupTaskId,
                        r.sourceSystem))
            .toList();
    List<ExamOrderDto.StatusEntry> entries = null;
    ExamOrderDto.CycleTimes cycle = null;
    if (full) {
      entries =
          history.byOrder(o.id).stream()
              .map(
                  h ->
                      new ExamOrderDto.StatusEntry(
                          ExamOrderStatus.fromWire(h.status), offset(h.occurredAt), h.reason))
              .toList();
      Instant followupAt =
          followupTask(rs)
              .filter(t -> t.status() == TaskStatus.COMPLETED)
              .map(t -> t.completedAt().toInstant())
              .orElse(null);
      cycle =
          new ExamOrderDto.CycleTimes(
              hours(o.requestedAt, o.scheduledAt),
              hours(o.scheduledAt, o.performedAt),
              hours(o.performedAt, o.reportedAt),
              hours(o.reportedAt, followupAt));
    }
    return new ExamOrderDto(
        o.id,
        o.citizenId,
        ExamOrderStatus.fromWire(o.status),
        offset(o.requestedAt),
        o.examCode,
        o.codeSystem,
        o.examDescription,
        o.category,
        o.priority,
        o.requestingCnes,
        full ? unitName(o.requestingCnes) : null,
        o.requestingProfessionalId,
        o.performerCnes,
        o.regulationRequestId,
        o.appointmentId,
        offset(o.scheduledAt),
        offset(o.performedAt),
        offset(o.reportedAt),
        o.careLine,
        computeIssues(o).stream().map(ExamIssue::fromWire).toList(),
        resultDtos,
        entries,
        cycle,
        o.sourceSystem,
        o.sourceRecordId,
        o.version);
  }

  private String unitName(String cnes) {
    if (!Cnes.isValid(cnes)) {
      return null;
    }
    return healthUnits.findByCnes(cnes).map(HealthUnitDto::name).orElse(null);
  }

  private static Double hours(Instant from, Instant to) {
    if (from == null || to == null) {
      return null;
    }
    return Math.round(Duration.between(from, to).toMinutes() / 60.0 * 100.0) / 100.0;
  }

  private static OffsetDateTime offset(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
