package br.gov.sus.nexus.core.hospital.application;

import br.gov.sus.nexus.core.careplan.api.CarePlanOrigin;
import br.gov.sus.nexus.core.careplan.api.CarePlanService;
import br.gov.sus.nexus.core.hospital.api.CounterReferralRegistration;
import br.gov.sus.nexus.core.hospital.api.DischargeFollowup;
import br.gov.sus.nexus.core.hospital.api.DischargeRegistration;
import br.gov.sus.nexus.core.hospital.api.HospitalEpisodeDto;
import br.gov.sus.nexus.core.hospital.api.HospitalEpisodeResult;
import br.gov.sus.nexus.core.hospital.api.HospitalEpisodeStatus;
import br.gov.sus.nexus.core.hospital.api.HospitalMovementRegistration;
import br.gov.sus.nexus.core.hospital.api.HospitalService;
import br.gov.sus.nexus.core.hospital.domain.CounterReferral;
import br.gov.sus.nexus.core.hospital.domain.HospitalBedMovement;
import br.gov.sus.nexus.core.hospital.domain.HospitalDischarge;
import br.gov.sus.nexus.core.hospital.domain.HospitalEpisode;
import br.gov.sus.nexus.core.hospital.domain.HospitalEpisodeSourceLink;
import br.gov.sus.nexus.core.hospital.infrastructure.HospitalRepositories;
import br.gov.sus.nexus.core.hospital.infrastructure.temporal.HospitalWorkflowStarter;
import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.CitizenService;
import br.gov.sus.nexus.core.identity.api.CitizenSummary;
import br.gov.sus.nexus.core.platform.errors.ConflictException;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.rules.RuleEvaluator;
import br.gov.sus.nexus.core.platform.rules.RuleSets;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.security.Roles;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import br.gov.sus.nexus.core.reference.api.HealthUnitDto;
import br.gov.sus.nexus.core.reference.api.HealthUnitService;
import br.gov.sus.nexus.core.regulation.api.RegulationService;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Episódios hospitalares (HOS-001..010) e fluxo pós-alta (CUI-006): registro ADT por vínculo de
 * origem, alta com risco por regra versionada ({@code platform.rule_set post-discharge-risk}),
 * tarefa {@code post_discharge_followup} (equipe → UBS → fila {@code busca_ativa}; prazo pela
 * {@code sla_policy} do risco), contrarreferência e desfecho do contato. O sumário de alta é só
 * referência ({@code summary_document_ref}); nenhum conteúdo clínico é armazenado.
 */
@ApplicationScoped
public class HospitalServiceImpl implements HospitalService {

  private static final Logger LOG = Logger.getLogger(HospitalServiceImpl.class);

  public static final String FOLLOWUP_ORIGIN_PREFIX = "discharge-followup:";
  public static final String ACTIVE_SEARCH_SUFFIX = ":active_search";
  public static final String RISK_RULE_SET = "post-discharge-risk";
  static final Duration READMISSION_WINDOW = Duration.ofDays(30);
  static final String FALLBACK_RISK = "medium";

  @Inject HospitalRepositories.Episodes episodes;
  @Inject HospitalRepositories.Movements movements;
  @Inject HospitalRepositories.Discharges discharges;
  @Inject HospitalRepositories.CounterReferrals counterReferrals;
  @Inject HospitalRepositories.SourceLinks links;
  @Inject HospitalEvents events;
  @Inject CidSensitivity cidSensitivity;
  @Inject RuleSets ruleSets;
  @Inject CitizenService citizens;
  @Inject HealthUnitService healthUnits;
  @Inject RegulationService regulation;
  @Inject CarePlanService carePlans;
  @Inject TaskCommands taskCommands;
  @Inject TaskQueries taskQueries;
  @Inject HospitalWorkflowStarter starter;
  @Inject TenantContext tenantContext;
  @Inject TenantTransactions transactions;
  @Inject CurrentActor currentActor;

  // ---------------------------------------------------------------------
  // ADT (HOS-001/002)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public HospitalEpisodeResult register(HospitalMovementRegistration reg) {
    if (!Cnes.isValid(reg.hospitalCnes())) {
      throw DomainValidationException.field("hospital_cnes", "CNES deve ter 7 dígitos");
    }
    String tenant = tenantContext.require();
    Instant occurredAt = micros(reg.occurredAt());
    Optional<HospitalEpisodeSourceLink> link =
        links.findBySource(tenant, reg.source().system(), reg.source().sourceRecordId());

    if (link.isEmpty()) {
      if (!"admit".equals(reg.movement())) {
        throw new NotFoundException(
            "episódio hospitalar", reg.source().system() + "/" + reg.source().sourceRecordId());
      }
      CitizenDetail citizen = resolveCitizen(reg.citizenRef());
      HospitalEpisode e = new HospitalEpisode();
      e.id = Ulid.generate(Ulid.HOSPITAL_EPISODE);
      e.tenantId = tenant;
      e.citizenId = citizen.id();
      e.hospitalCnes = reg.hospitalCnes().trim();
      e.episodeClass = reg.episodeClass();
      e.status = HospitalEpisodeStatus.ADMITTED.wire();
      e.admittedAt = occurredAt;
      e.sourceSystem = reg.source().system();
      e.sourceRecordId = reg.source().sourceRecordId();
      e.sourceRecordVersion = reg.source().sourceRecordVersion();
      copy(reg, e);
      linkRegulation(e, reg.source().system(), reg.regulationSourceRecordId());
      episodes.persist(e);

      HospitalEpisodeSourceLink l = new HospitalEpisodeSourceLink();
      l.id = Ulid.generate(Ulid.SOURCE_LINK);
      l.tenantId = tenant;
      l.episodeId = e.id;
      l.sourceSystem = reg.source().system();
      l.connector = reg.source().connector();
      l.sourceRecordId = reg.source().sourceRecordId();
      l.sourceRecordVersion = reg.source().sourceRecordVersion();
      links.persist(l);

      HospitalBedMovement m = movement(e, "admit", occurredAt, reg, null, null);
      events.publishAdt("admitted", e, m, reg.source(), null);
      LOG.infof("episódio %s admitido em %s (%s)", e.id, e.hospitalCnes, e.episodeClass);
      return new HospitalEpisodeResult(toDto(e), true);
    }

    HospitalEpisode e = load(link.get().episodeId);
    Instant now = Instant.now();
    link.get().sourceRecordVersion = reg.source().sourceRecordVersion();
    link.get().updatedAt = now;
    switch (reg.movement()) {
      case "admit" -> {
        boolean changed = copy(reg, e);
        changed |= linkRegulation(e, reg.source().system(), reg.regulationSourceRecordId());
        if (changed) {
          e.sourceRecordVersion = reg.source().sourceRecordVersion();
          e.updatedAt = now;
        }
      }
      case "transfer", "bed_change" -> {
        requireOpen(e);
        String previousWard = e.ward;
        String previousBed = e.bed;
        copy(reg, e);
        e.status = HospitalEpisodeStatus.IN_PROGRESS.wire();
        e.updatedAt = now;
        HospitalBedMovement m =
            movement(e, reg.movement(), occurredAt, reg, previousWard, previousBed);
        events.publishAdt(
            "transfer".equals(reg.movement()) ? "transferred" : "bed_changed",
            e,
            m,
            reg.source(),
            null);
      }
      case "discharge", "death" -> {
        requireOpen(e);
        copy(reg, e);
        boolean death = "death".equals(reg.movement());
        DischargeRegistration d =
            new DischargeRegistration(
                reg.source(),
                reg.occurredAt(),
                death ? "deceased" : "home",
                reg.principalDiagnosisCid(),
                null,
                null,
                null,
                null,
                null,
                null);
        applyDischarge(e, d, reg.movement(), reg.reason());
      }
      case "cancel" -> {
        requireOpen(e);
        e.status = HospitalEpisodeStatus.CANCELLED.wire();
        e.updatedAt = now;
        movement(e, "cancel", occurredAt, reg, null, null);
        LOG.infof("episódio %s cancelado pela origem", e.id);
      }
      default -> throw DomainValidationException.field("movement", "movimento desconhecido");
    }
    return new HospitalEpisodeResult(toDto(e), false);
  }

  private HospitalBedMovement movement(
      HospitalEpisode e,
      String movement,
      Instant occurredAt,
      HospitalMovementRegistration reg,
      String previousWard,
      String previousBed) {
    HospitalBedMovement m = new HospitalBedMovement();
    m.id = Ulid.generate(Ulid.BED_MOVEMENT);
    m.tenantId = e.tenantId;
    m.episodeId = e.id;
    m.movement = movement;
    m.occurredAt = occurredAt;
    m.recordedAt = Instant.now();
    m.ward = reg == null ? e.ward : blank(reg.ward());
    m.bed = reg == null ? e.bed : blank(reg.bed());
    m.previousWard = previousWard;
    m.previousBed = previousBed;
    m.attendingProfessionalId = reg == null ? null : blank(reg.attendingProfessionalId());
    m.reason = reg == null ? null : blank(reg.reason());
    m.sourceSystem = reg == null ? e.sourceSystem : reg.source().system();
    m.actorId = currentActor.actorId();
    movements.persist(m);
    return m;
  }

  private boolean copy(HospitalMovementRegistration reg, HospitalEpisode e) {
    boolean changed = false;
    changed |= set(e.episodeClass, reg.episodeClass(), v -> e.episodeClass = v);
    if (blank(reg.ward()) != null) {
      changed |= set(e.ward, reg.ward().trim(), v -> e.ward = v);
    }
    if (blank(reg.bed()) != null) {
      changed |= set(e.bed, reg.bed().trim(), v -> e.bed = v);
    }
    if (blank(reg.attendingProfessionalId()) != null) {
      changed |=
          set(
              e.attendingProfessionalId,
              reg.attendingProfessionalId().trim(),
              v -> e.attendingProfessionalId = v);
    }
    if (blank(reg.admissionSource()) != null) {
      changed |= set(e.admissionSource, reg.admissionSource(), v -> e.admissionSource = v);
    }
    if (blank(reg.aihNumber()) != null) {
      changed |= set(e.aihNumber, reg.aihNumber().trim(), v -> e.aihNumber = v);
    }
    changed |= diagnosis(e, reg.principalDiagnosisCid());
    return changed;
  }

  /** CID só como código; classificação {@code highly_restricted} por prefixo configurável. */
  private boolean diagnosis(HospitalEpisode e, String cid) {
    String code = CidSensitivity.normalize(cid);
    if (code == null) {
      return false;
    }
    boolean changed = set(e.principalDiagnosisCid, code, v -> e.principalDiagnosisCid = v);
    e.cidHighlyRestricted = cidSensitivity.isHighlyRestricted(code);
    return changed;
  }

  private boolean linkRegulation(HospitalEpisode e, String system, String regulationSourceId) {
    if (e.regulationRequestId != null || blank(regulationSourceId) == null) {
      return false;
    }
    return regulation
        .findBySourceRecord(system, regulationSourceId.trim())
        .map(
            r -> {
              e.regulationRequestId = r.id();
              if (e.admissionSource == null) {
                e.admissionSource = "regulation";
              }
              return true;
            })
        .orElse(false);
  }

  private static void requireOpen(HospitalEpisode e) {
    if (HospitalEpisodeStatus.fromWire(e.status).isClosed()) {
      throw new ConflictException("episódio hospitalar já encerrado (" + e.status + ")");
    }
  }

  // ---------------------------------------------------------------------
  // alta (HOS-003/004/005)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public HospitalEpisodeDto discharge(String episodeId, DischargeRegistration d) {
    HospitalEpisode e = load(episodeId);
    requireOpen(e);
    applyDischarge(e, d, "deceased".equals(d.disposition()) ? "death" : "discharge", null);
    return toDto(e);
  }

  @Override
  @TenantTransactional
  public HospitalEpisodeDto dischargeBySource(
      String sourceSystem, String sourceRecordId, DischargeRegistration d) {
    HospitalEpisodeSourceLink link =
        links
            .findBySource(tenantContext.require(), sourceSystem, sourceRecordId)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "episódio hospitalar", sourceSystem + "/" + sourceRecordId));
    return discharge(link.episodeId, d);
  }

  /**
   * Fluxo de alta: LOS, reinternação em 30 d, UBS/equipe de referência, risco por regra versionada,
   * registro {@code hospital_discharge}, eventos ADT + {@code discharge.completed} e, salvo óbito,
   * tarefa de contato pós-alta (o workflow é iniciado pelo consumidor do evento).
   */
  private void applyDischarge(
      HospitalEpisode e, DischargeRegistration d, String movementKind, String reason) {
    Instant now = Instant.now();
    Instant dischargedAt = micros(d.dischargedAt());
    if (dischargedAt.isBefore(e.admittedAt)) {
      throw DomainValidationException.field(
          "discharged_at", "alta anterior à admissão do episódio");
    }
    boolean death = "deceased".equals(d.disposition());
    e.dischargedAt = dischargedAt;
    e.disposition = d.disposition();
    e.lengthOfStayDays = (int) Duration.between(e.admittedAt, dischargedAt).toDays();
    e.status = (death ? HospitalEpisodeStatus.DECEASED : HospitalEpisodeStatus.DISCHARGED).wire();
    diagnosis(e, d.principalDiagnosisCid());
    if (d.proceduresCount() != null) {
      e.proceduresCount = d.proceduresCount();
    }
    e.followupPlanPresent = d.followupPlanPresent();
    e.followupDueDays = d.followupDueDays();
    e.careLines = normalizeCareLines(d.careLines());
    e.summaryDocumentRef = blank(d.summaryDocumentRef());
    e.summaryDocumentSha256 =
        d.summaryDocumentSha256() == null ? null : d.summaryDocumentSha256().toLowerCase();

    // reinternação em 30 dias (HOS-006)
    Optional<HospitalEpisode> previous =
        episodes.previousDischarged(e.citizenId, e.admittedAt, e.id);
    e.readmissionWithin30d =
        previous.isPresent()
            && !previous.get().dischargedAt.plus(READMISSION_WINDOW).isBefore(e.admittedAt);
    e.previousEpisodeId = e.readmissionWithin30d ? previous.get().id : null;

    // referência territorial (identity)
    CitizenDetail citizen = safeCitizen(e.citizenId);
    if (citizen != null) {
      e.referenceHealthUnitCnes =
          Cnes.isValid(citizen.healthUnitCnes()) ? citizen.healthUnitCnes() : null;
      e.referenceTeamIne = blank(citizen.teamIne());
      e.referenceMicroarea = blank(citizen.microarea());
    }

    // risco por regra versionada (HOS-005)
    Map<String, Object> facts = riskFacts(e, citizen);
    Optional<RuleSets.RuleVersion> rule = ruleSets.current(RISK_RULE_SET);
    e.riskLevel =
        rule.flatMap(r -> RuleEvaluator.decide(r.definition(), facts)).orElse(FALLBACK_RISK);
    e.riskRuleVersion = rule.map(r -> r.label(RISK_RULE_SET)).orElse(RISK_RULE_SET + "/fallback");
    e.updatedAt = now;

    HospitalDischarge h = new HospitalDischarge();
    h.id = Ulid.generate(Ulid.DISCHARGE);
    h.tenantId = e.tenantId;
    h.episodeId = e.id;
    h.dischargedAt = dischargedAt;
    h.disposition = e.disposition;
    h.lengthOfStayDays = e.lengthOfStayDays;
    h.proceduresCount = e.proceduresCount;
    h.followupPlanPresent = e.followupPlanPresent;
    h.followupDueDays = e.followupDueDays;
    h.careLines = e.careLines;
    h.readmissionWithin30d = e.readmissionWithin30d;
    h.riskLevel = e.riskLevel;
    h.riskRuleVersion = e.riskRuleVersion;
    h.riskFacts = facts;
    h.summaryDocumentRef = e.summaryDocumentRef;
    h.summaryDocumentSha256 = e.summaryDocumentSha256;
    h.sourceSystem = d.source().system();
    h.sourceRecordId = d.source().sourceRecordId();
    h.actorId = currentActor.actorId();
    discharges.persist(h);

    HospitalBedMovement m = movement(e, movementKind, dischargedAt, null, null, null);
    m.reason = blank(reason);
    String adt = events.publishAdt(death ? "deceased" : "discharged", e, m, d.source(), null);
    events.publishDischarge("completed", e, d.source(), false, adt);

    if (!death) {
      openFollowup(e);
    } else {
      e.followupStatus = "closed";
      e.followupOutcome = "deceased";
    }
    LOG.infof(
        "alta do episódio %s: LOS=%d d, risco=%s (%s), reinternação=%s",
        e.id, e.lengthOfStayDays, e.riskLevel, e.riskRuleVersion, e.readmissionWithin30d);
  }

  /** Fatos permitidos à tabela de decisão (reprodutibilidade: gravados em risk_facts). */
  static Map<String, Object> riskFacts(HospitalEpisode e, CitizenDetail citizen) {
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("length_of_stay_days", e.lengthOfStayDays);
    facts.put("disposition", e.disposition);
    facts.put("readmission_within_30d", e.readmissionWithin30d);
    if (citizen != null && citizen.birthdate() != null) {
      facts.put("age_years", Period.between(citizen.birthdate(), LocalDate.now()).getYears());
    }
    facts.put("care_lines", Arrays.asList(e.careLines));
    if (e.followupPlanPresent != null) {
      facts.put("followup_plan_present", e.followupPlanPresent);
    }
    if (e.episodeClass != null) {
      facts.put("episode_class", e.episodeClass);
    }
    return facts;
  }

  /** Tarefa de contato pós-alta: prioridade = risco; equipe → UBS → fila; prazo pela sla_policy. */
  private void openFollowup(HospitalEpisode e) {
    String originId = FOLLOWUP_ORIGIN_PREFIX + e.id;
    TaskPriority priority = priorityFor(e.riskLevel);
    Optional<SlaPolicyDto> policy =
        taskQueries.slaPolicy(TaskType.POST_DISCHARGE_FOLLOWUP, priority);
    Instant dueAt = Instant.now().plus(policy.map(SlaPolicyDto::dueIn).orElse(Duration.ofDays(3)));
    Optional<TaskDto> existing = taskQueries.findOpenByOrigin("workflow", originId);
    TaskDto task =
        existing.orElseGet(
            () ->
                taskCommands.create(
                    new TaskCreate(
                        TaskType.POST_DISCHARGE_FOLLOWUP,
                        priority,
                        "Contato pós-alta hospitalar (risco " + e.riskLevel + ")",
                        "Alta do episódio "
                            + e.id
                            + " em "
                            + e.dischargedAt.atOffset(ZoneOffset.UTC)
                            + " (risco "
                            + e.riskLevel
                            + ", regra "
                            + e.riskRuleVersion
                            + "). Contatar o cidadão, confirmar o plano de seguimento e registrar"
                            + " o desfecho em /api/v1/hospital/episodes/"
                            + e.id
                            + "/followup.",
                        e.citizenId,
                        assignee(e),
                        dueAt.atOffset(ZoneOffset.UTC),
                        policy.map(SlaPolicyDto::id).orElse(null),
                        TaskOrigin.workflow(originId, e.riskRuleVersion)),
                    null));
    e.followupStatus = "pending";
    e.followupTaskId = task.id();
    e.followupDueAt = task.dueAt() == null ? dueAt : task.dueAt().toInstant();
    e.followupOutcome = null;
    e.followupContactedAt = null;
  }

  static TaskPriority priorityFor(String risk) {
    return switch (risk == null ? "" : risk) {
      case "high" -> TaskPriority.HIGH;
      case "low" -> TaskPriority.LOW;
      default -> TaskPriority.MEDIUM;
    };
  }

  private static Assignee assignee(HospitalEpisode e) {
    if (e.referenceTeamIne != null) {
      return Assignee.team(e.referenceTeamIne);
    }
    if (Cnes.isValid(e.referenceHealthUnitCnes)) {
      return Assignee.healthUnit(e.referenceHealthUnitCnes);
    }
    return Assignee.queue("busca_ativa");
  }

  // ---------------------------------------------------------------------
  // contrarreferência (HOS-008)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public HospitalEpisodeDto counterReferral(String episodeId, CounterReferralRegistration reg) {
    HospitalEpisode e = load(episodeId);
    if (e.dischargedAt == null) {
      throw new ConflictException("contrarreferência exige episódio com alta registrada");
    }
    if (reg.targetHealthUnitCnes() != null
        && !reg.targetHealthUnitCnes().isBlank()
        && !Cnes.isValid(reg.targetHealthUnitCnes())) {
      throw DomainValidationException.field("target_health_unit_cnes", "CNES deve ter 7 dígitos");
    }
    CounterReferral c = new CounterReferral();
    c.id = Ulid.generate(Ulid.COUNTER_REFERRAL);
    c.tenantId = e.tenantId;
    c.episodeId = e.id;
    c.receivedAt = micros(reg.receivedAt());
    c.targetHealthUnitCnes = blank(reg.targetHealthUnitCnes());
    c.documentRef = blank(reg.documentRef());
    c.documentSha256 = reg.documentSha256() == null ? null : reg.documentSha256().toLowerCase();
    c.recommendationsCount = reg.recommendationsCount();
    c.sourceSystem = reg.source().system();
    c.sourceRecordId = reg.source().sourceRecordId();
    counterReferrals.persist(c);
    e.updatedAt = Instant.now();
    events.publishDischarge(
        "counter_referral_received", e, reg.source(), c.documentRef != null, null);
    return toDto(e);
  }

  // ---------------------------------------------------------------------
  // desfecho do contato (CUI-006)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public HospitalEpisodeDto followup(String episodeId, DischargeFollowup f) {
    HospitalEpisode e = load(episodeId);
    if (e.dischargedAt == null) {
      throw new ConflictException("episódio sem alta registrada");
    }
    if (!"pending".equals(e.followupStatus) && !"escalated".equals(e.followupStatus)) {
      throw new ConflictException("acompanhamento pós-alta já encerrado");
    }
    Instant now = Instant.now();
    e.followupOutcome = f.outcome();
    e.followupContactedAt = f.contactedAt() == null ? now : micros(f.contactedAt());
    e.followupStatus =
        switch (f.outcome()) {
          case "appointment_scheduled" -> "scheduled";
          case "contact_made" -> "contacted";
          default -> "closed";
        };
    e.updatedAt = now;
    closeFollowupTasks(e, f.outcome(), f.note());
    carePlans.resolveGapsByOrigin(e.id, gapResolution(f.outcome()), f.note());
    if (f.contactEstablished()) {
      for (String line : e.careLines) {
        Optional<String> plan =
            carePlans.openPlanForCareLine(
                e.citizenId, line, CarePlanOrigin.hospitalDischarge(e.id));
        if (plan.isPresent()) {
          e.followupCarePlanId = plan.get();
          break;
        }
      }
    }
    starter.signalContact(e.id, f.outcome());
    LOG.infof("pós-alta %s: desfecho %s (%s)", e.id, f.outcome(), e.followupStatus);
    return toDto(e);
  }

  static String gapResolution(String outcome) {
    return switch (outcome) {
      case "appointment_scheduled" -> "scheduled";
      case "contact_made", "deceased", "moved", "refused", "not_found" -> outcome;
      default -> "cancelled";
    };
  }

  private void closeFollowupTasks(HospitalEpisode e, String outcome, String note) {
    String originId = FOLLOWUP_ORIGIN_PREFIX + e.id;
    taskCommands.completeByOrigin("workflow", originId, outcome, note);
    taskCommands.completeByOrigin("workflow", originId + ACTIVE_SEARCH_SUFFIX, outcome, note);
  }

  // ---------------------------------------------------------------------
  // suporte ao DischargeFollowUpWorkflow
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public Optional<FollowupSnapshot> followupSnapshot(String episodeId) {
    HospitalEpisode e = episodes.findById(episodeId);
    if (e == null || e.followupStatus == null) {
      return Optional.empty();
    }
    boolean contacted =
        "contacted".equals(e.followupStatus) || "scheduled".equals(e.followupStatus);
    boolean closed = "closed".equals(e.followupStatus);
    return Optional.of(
        new FollowupSnapshot(
            e.followupOutcome != null ? e.followupOutcome : e.followupStatus,
            e.followupDueAt == null ? null : e.followupDueAt.toString(),
            contacted,
            closed,
            e.riskLevel));
  }

  @Override
  @TenantTransactional
  public boolean escalateFollowup(String episodeId) {
    HospitalEpisode e = episodes.findById(episodeId);
    if (e == null || !"pending".equals(e.followupStatus)) {
      return false;
    }
    Instant now = Instant.now();
    e.followupStatus = "escalated";
    e.updatedAt = now;
    escalateTask(e.followupTaskId);
    String originId = FOLLOWUP_ORIGIN_PREFIX + e.id + ACTIVE_SEARCH_SUFFIX;
    if (taskQueries.findOpenByOrigin("workflow", originId).isEmpty()) {
      taskCommands.create(
          new TaskCreate(
              TaskType.ACTIVE_SEARCH,
              TaskPriority.HIGH,
              "Busca ativa pós-alta"
                  + (e.referenceMicroarea == null ? "" : " — microárea " + e.referenceMicroarea),
              "Sem contato após a alta do episódio "
                  + e.id
                  + " (risco "
                  + e.riskLevel
                  + "). Realizar busca ativa"
                  + (e.referenceMicroarea == null ? "" : " na microárea " + e.referenceMicroarea)
                  + " e registrar o desfecho em /api/v1/hospital/episodes/"
                  + e.id
                  + "/followup.",
              e.citizenId,
              assignee(e),
              null,
              null,
              TaskOrigin.workflow(originId, e.riskRuleVersion)),
          null);
    }
    carePlans.openPostDischargeNoContactGap(
        e.citizenId,
        e.careLines.length == 0 ? null : e.careLines[0],
        e.id,
        e.followupDueAt == null ? null : e.followupDueAt.atOffset(ZoneOffset.UTC),
        e.referenceHealthUnitCnes,
        e.referenceTeamIne,
        e.referenceMicroarea);
    LOG.infof("pós-alta %s escalonado: busca ativa aberta", e.id);
    return true;
  }

  /**
   * Escalona a tarefa de contato em transação própria: o {@code TaskSlaWorkflow} pode estar
   * escalonando a mesma tarefa pela {@code sla_policy} (conflito otimista/estado inválido não pode
   * marcar a transação da activity como rollback-only).
   */
  private void escalateTask(String taskId) {
    if (taskId == null) {
      return;
    }
    try {
      transactions.requiringNew(
          () -> {
            TaskDto t = taskQueries.get(taskId);
            if (t.status().isFinal() || t.status() == TaskStatus.ESCALATED) {
              return null;
            }
            return taskCommands.transition(
                taskId,
                TaskTransition.escalate(
                    Assignee.queue("busca_ativa"), "prazo de contato pós-alta vencido"));
          });
    } catch (RuntimeException ex) {
      LOG.debugf("tarefa %s não escalonada: %s", taskId, ex.getClass().getSimpleName());
    }
  }

  @Override
  @TenantTransactional
  public boolean closeFollowupNotFound(String episodeId) {
    HospitalEpisode e = episodes.findById(episodeId);
    if (e == null || !"pending".equals(e.followupStatus) && !"escalated".equals(e.followupStatus)) {
      return false;
    }
    Instant now = Instant.now();
    e.followupStatus = "closed";
    e.followupOutcome = "not_found";
    e.updatedAt = now;
    closeFollowupTasks(e, "not_found", "segundo prazo de busca ativa vencido");
    carePlans.resolveGapsByOrigin(e.id, "not_found", "segundo prazo de busca ativa vencido");
    LOG.infof("pós-alta %s encerrado como not_found", e.id);
    return true;
  }

  // ---------------------------------------------------------------------
  // consultas
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public HospitalEpisodeDto get(String episodeId) {
    return toDto(load(episodeId));
  }

  @Override
  @TenantTransactional
  public Optional<HospitalEpisodeDto> findBySource(String sourceSystem, String sourceRecordId) {
    if (sourceSystem == null || sourceRecordId == null) {
      return Optional.empty();
    }
    return links
        .findBySource(tenantContext.require(), sourceSystem, sourceRecordId)
        .map(l -> episodes.findById(l.episodeId))
        .filter(Objects::nonNull)
        .map(this::toDto);
  }

  @Override
  @TenantTransactional
  public Page<HospitalEpisodeDto> list(
      String citizenId,
      String hospitalCnes,
      HospitalEpisodeStatus status,
      OffsetDateTime dischargedFrom,
      OffsetDateTime dischargedTo,
      String referenceCnes,
      String followupStatus,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    List<HospitalEpisodeDto> rows =
        episodes
            .list(
                blank(citizenId),
                blank(hospitalCnes),
                status == null ? null : status.wire(),
                dischargedFrom == null ? null : dischargedFrom.toInstant(),
                dischargedTo == null ? null : dischargedTo.toInstant(),
                blank(referenceCnes),
                blank(followupStatus),
                Cursor.decode(cursor).orElse(null),
                size + 1)
            .stream()
            .map(this::toDto)
            .toList();
    return Page.of(rows, size, HospitalEpisodeDto::id);
  }

  @Override
  @TenantTransactional
  public Optional<OffsetDateTime> lastDischargeAt(String citizenId) {
    return episodes.lastDischargeAt(citizenId).map(i -> i.atOffset(ZoneOffset.UTC));
  }

  // ---------------------------------------------------------------------

  /**
   * CID principal só para papéis clínicos com vínculo (CNES do hospital/UBS de referência ou
   * equipe); CID {@code highly_restricted} só para a equipe de referência ou profissional
   * hospitalar do CNES. {@code admin_municipal} vê tudo.
   */
  boolean cidVisible(HospitalEpisode e) {
    if (e.principalDiagnosisCid == null) {
      return false;
    }
    if (currentActor.hasRole(Roles.ADMIN_MUNICIPAL)) {
      return true;
    }
    boolean clinical =
        currentActor.hasRole(Roles.PROFISSIONAL_APS)
            || currentActor.hasRole(Roles.PROFISSIONAL_HOSPITALAR)
            || currentActor.hasRole(Roles.REGULADOR);
    if (!clinical) {
      return false;
    }
    List<String> cnes = currentActor.cnes();
    List<String> teams = currentActor.teams();
    boolean teamMember = e.referenceTeamIne != null && teams.contains(e.referenceTeamIne);
    boolean hospitalStaff =
        currentActor.hasRole(Roles.PROFISSIONAL_HOSPITALAR) && cnes.contains(e.hospitalCnes);
    if (e.cidHighlyRestricted) {
      return teamMember || hospitalStaff;
    }
    boolean linked =
        teamMember
            || cnes.contains(e.hospitalCnes)
            || (e.referenceHealthUnitCnes != null && cnes.contains(e.referenceHealthUnitCnes))
            || currentActor.hasRole(Roles.REGULADOR);
    return linked;
  }

  HospitalEpisodeDto toDto(HospitalEpisode e) {
    List<HospitalEpisodeDto.Movement> moves =
        movements.byEpisode(e.id).stream()
            .map(
                m ->
                    new HospitalEpisodeDto.Movement(
                        m.movement, offset(m.occurredAt), m.ward, m.bed))
            .toList();
    HospitalEpisodeDto.CounterReferral cr =
        counterReferrals
            .latest(e.id)
            .map(
                c ->
                    new HospitalEpisodeDto.CounterReferral(
                        offset(c.receivedAt), c.documentRef != null, c.recommendationsCount))
            .orElse(null);
    HospitalEpisodeDto.Followup followup =
        e.followupStatus == null
            ? null
            : new HospitalEpisodeDto.Followup(
                e.followupStatus,
                e.followupTaskId,
                offset(e.followupDueAt),
                e.followupOutcome,
                offset(e.followupContactedAt),
                e.followupCarePlanId);
    HospitalEpisodeDto dto =
        new HospitalEpisodeDto(
            e.id,
            e.citizenId,
            e.hospitalCnes,
            healthUnits.findByCnes(e.hospitalCnes).map(HealthUnitDto::name).orElse(null),
            e.episodeClass,
            HospitalEpisodeStatus.fromWire(e.status),
            offset(e.admittedAt),
            offset(e.dischargedAt),
            e.lengthOfStayDays,
            e.disposition,
            e.ward,
            e.bed,
            e.admissionSource,
            e.regulationRequestId,
            e.principalDiagnosisCid,
            e.aihNumber,
            e.readmissionWithin30d,
            e.previousEpisodeId,
            e.referenceHealthUnitCnes,
            e.referenceTeamIne,
            e.riskLevel,
            e.riskRuleVersion,
            Arrays.asList(e.careLines),
            followup,
            cr,
            e.summaryDocumentRef != null,
            moves,
            e.sourceSystem,
            e.sourceRecordId,
            e.version);
    return cidVisible(e) ? dto : dto.withoutCid();
  }

  private CitizenDetail resolveCitizen(HospitalMovementRegistration.CitizenRef ref) {
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
        new HospitalMovementRegistration.CitizenRef(found.items().get(0).id(), null, null));
  }

  private CitizenDetail safeCitizen(String citizenId) {
    try {
      return citizens.get(citizenId);
    } catch (RuntimeException e) {
      return null;
    }
  }

  static String[] normalizeCareLines(List<String> lines) {
    if (lines == null) {
      return new String[0];
    }
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String l : lines) {
      if (l != null && !l.isBlank()) {
        out.add(l.trim().toLowerCase(Locale.ROOT));
      }
    }
    return new ArrayList<>(out).toArray(String[]::new);
  }

  private HospitalEpisode load(String id) {
    HospitalEpisode e = episodes.findById(id);
    if (e == null) {
      throw new NotFoundException("episódio hospitalar", id);
    }
    return e;
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

  private static OffsetDateTime offset(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
