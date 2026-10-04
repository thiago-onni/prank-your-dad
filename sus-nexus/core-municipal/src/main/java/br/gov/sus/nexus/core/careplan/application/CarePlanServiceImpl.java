package br.gov.sus.nexus.core.careplan.application;

import br.gov.sus.nexus.core.careplan.api.CareGapDto;
import br.gov.sus.nexus.core.careplan.api.CareGapKind;
import br.gov.sus.nexus.core.careplan.api.CareGapResolve;
import br.gov.sus.nexus.core.careplan.api.CarePlanClose;
import br.gov.sus.nexus.core.careplan.api.CarePlanCreate;
import br.gov.sus.nexus.core.careplan.api.CarePlanDto;
import br.gov.sus.nexus.core.careplan.api.CarePlanItemDto;
import br.gov.sus.nexus.core.careplan.api.CarePlanItemUpdate;
import br.gov.sus.nexus.core.careplan.api.CarePlanOrigin;
import br.gov.sus.nexus.core.careplan.api.CarePlanService;
import br.gov.sus.nexus.core.careplan.api.ProtocolCreate;
import br.gov.sus.nexus.core.careplan.api.ProtocolDto;
import br.gov.sus.nexus.core.careplan.api.ProtocolItemRule;
import br.gov.sus.nexus.core.careplan.api.ProtocolTransition;
import br.gov.sus.nexus.core.careplan.domain.CareGap;
import br.gov.sus.nexus.core.careplan.domain.CarePlan;
import br.gov.sus.nexus.core.careplan.domain.CarePlanItem;
import br.gov.sus.nexus.core.careplan.domain.Protocol;
import br.gov.sus.nexus.core.careplan.domain.ProtocolVersion;
import br.gov.sus.nexus.core.careplan.infrastructure.CarePlanRepositories;
import br.gov.sus.nexus.core.consent.api.ConsentService;
import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.CitizenService;
import br.gov.sus.nexus.core.platform.errors.ConflictException;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.errors.ProblemException;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.rules.RuleEvaluator;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.security.Roles;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
import br.gov.sus.nexus.core.tasks.api.Assignee;
import br.gov.sus.nexus.core.tasks.api.TaskCommands;
import br.gov.sus.nexus.core.tasks.api.TaskCreate;
import br.gov.sus.nexus.core.tasks.api.TaskDto;
import br.gov.sus.nexus.core.tasks.api.TaskOrigin;
import br.gov.sus.nexus.core.tasks.api.TaskPriority;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import br.gov.sus.nexus.core.tasks.api.TaskType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Protocolos configuráveis, planos de cuidado e lacunas (CUI-001..009). Elegibilidade e condições
 * de item são avaliadas pelo {@link RuleEvaluator} sobre fatos do cidadão (idade, sexo) — sem
 * código. Toda lacuna aberta gera tarefa {@code care_gap} para a equipe e evento {@code
 * sus.caregap.detected}.
 */
@ApplicationScoped
public class CarePlanServiceImpl implements CarePlanService {

  private static final Logger LOG = Logger.getLogger(CarePlanServiceImpl.class);

  public static final String GAP_ORIGIN_PREFIX = "care-gap:";
  static final Duration EVIDENCE_WINDOW = Duration.ofDays(30);
  static final Set<String> CONSULTATION_KINDS = Set.of("consultation", "return");
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
      new TypeReference<>() {};

  @Inject CarePlanRepositories.Protocols protocols;
  @Inject CarePlanRepositories.Versions versions;
  @Inject CarePlanRepositories.Plans plans;
  @Inject CarePlanRepositories.Items items;
  @Inject CarePlanRepositories.Gaps gaps;
  @Inject CarePlanEvents events;
  @Inject CitizenService citizens;
  @Inject ConsentService consent;
  @Inject TaskCommands taskCommands;
  @Inject TaskQueries taskQueries;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;
  @Inject ObjectMapper objectMapper;

  // ---------------------------------------------------------------------
  // protocolos (CUI-009)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ProtocolDto createProtocolVersion(ProtocolCreate c) {
    String tenant = tenantContext.require();
    String careLine = c.careLine().trim().toLowerCase();
    Protocol protocol =
        protocols
            .findByLineAndName(careLine, c.name().trim())
            .orElseGet(
                () -> {
                  Protocol p = new Protocol();
                  p.id = Ulid.generate(Ulid.PROTOCOL);
                  p.tenantId = tenant;
                  p.careLine = careLine;
                  p.name = c.name().trim();
                  protocols.persist(p);
                  return p;
                });
    for (ProtocolItemRule r : c.items()) {
      if (r.condition() != null) {
        validateCondition(objectMapper.valueToTree(r.condition()));
      }
    }
    if (c.eligibility() != null) {
      validateCondition(objectMapper.valueToTree(c.eligibility()));
    }
    List<ProtocolVersion> existing = versions.byProtocol(protocol.id);
    int next =
        existing.stream().mapToInt(v -> parseVersion(v.version)).max().orElse(0) + 1;
    ProtocolVersion base =
        c.baseVersion() == null
            ? null
            : existing.stream()
                .filter(v -> v.version.equals(c.baseVersion().trim()))
                .findFirst()
                .orElseThrow(
                    () ->
                        DomainValidationException.field(
                            "base_version", "versão base inexistente: " + c.baseVersion()));
    ProtocolVersion v = new ProtocolVersion();
    v.id = Ulid.generate(Ulid.PROTOCOL_VERSION);
    v.tenantId = tenant;
    v.protocolId = protocol.id;
    v.version = String.valueOf(next);
    v.status = "draft";
    v.description = c.description() == null && base != null ? base.description : c.description();
    v.eligibility =
        c.eligibility() != null
            ? c.eligibility()
            : (base != null ? base.eligibility : Map.of());
    v.items = c.items().stream().map(r -> objectMapper.convertValue(r, MAP)).toList();
    v.testCases = c.testCases() == null ? List.of() : c.testCases();
    v.lostToFollowupDays =
        c.lostToFollowupDays() != null
            ? c.lostToFollowupDays()
            : (base != null ? base.lostToFollowupDays : 90);
    v.createdBy = currentActor.actorId();
    versions.persist(v);
    return toDto(protocol, v);
  }

  @Override
  @TenantTransactional
  public ProtocolDto transitionProtocol(String protocolId, String version, ProtocolTransition t) {
    Protocol protocol = loadProtocol(protocolId);
    ProtocolVersion v =
        versions
            .find(protocolId, version)
            .orElseThrow(() -> new NotFoundException("versão de protocolo", version));
    if (v.tenantId == null) {
      throw new ConflictException("versão global não pode ser alterada pelo município");
    }
    Instant now = Instant.now();
    switch (t.action()) {
      case "submit" -> {
        require(v, "draft");
        v.status = "in_review";
      }
      case "approve" -> {
        require(v, "in_review");
        if (!(currentActor.hasRole(Roles.GESTOR) || currentActor.hasRole(Roles.ADMIN_MUNICIPAL))) {
          throw new ProblemException(
              403,
              "Acesso negado",
              "aprovação de protocolo exige papel gestor ou admin_municipal",
              "urn:sus-nexus:problem:forbidden");
        }
        if (v.testCases == null || v.testCases.isEmpty()) {
          throw DomainValidationException.field(
              "test_cases", "aprovação exige casos de teste anexados (plano §8.3)");
        }
        v.status = "approved";
        v.approvedBy = currentActor.actorId();
        v.approvedAt = now;
      }
      case "activate" -> {
        require(v, "approved");
        for (ProtocolVersion other : versions.tenantActive(protocolId, v.tenantId)) {
          other.status = "revoked";
          other.updatedAt = now;
        }
        v.status = "active";
        v.effectiveFrom = t.effectiveFrom() == null ? now : t.effectiveFrom().toInstant();
      }
      case "revoke" -> {
        if (!"active".equals(v.status) && !"approved".equals(v.status)) {
          throw new ConflictException("revoke inválido no estado " + v.status);
        }
        v.status = "revoked";
      }
      default -> throw DomainValidationException.field("action", "ação desconhecida");
    }
    v.updatedAt = now;
    LOG.infof("protocolo %s v%s → %s (%s)", protocolId, version, v.status, t.action());
    return toDto(protocol, v);
  }

  private static void require(ProtocolVersion v, String expected) {
    if (!expected.equals(v.status)) {
      throw new ConflictException(
          "transição inválida: versão em " + v.status + " (esperado " + expected + ")");
    }
  }

  @Override
  @TenantTransactional
  public List<ProtocolDto> listProtocols(String careLine, String status) {
    List<String> ids = null;
    Map<String, Protocol> byId = new HashMap<>();
    if (careLine != null && !careLine.isBlank()) {
      List<Protocol> ps = protocols.byCareLine(careLine.trim().toLowerCase());
      ps.forEach(p -> byId.put(p.id, p));
      ids = ps.stream().map(p -> p.id).toList();
    } else {
      protocols.listAll().forEach(p -> byId.put(p.id, p));
    }
    return versions.listAll(careLine, blank(status), ids).stream()
        .filter(v -> byId.containsKey(v.protocolId))
        .map(v -> toDto(byId.get(v.protocolId), v))
        .toList();
  }

  // ---------------------------------------------------------------------
  // planos (CUI-001/002)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public CarePlanDto create(CarePlanCreate c) {
    Protocol protocol = loadProtocol(c.protocolId());
    ProtocolVersion v =
        c.protocolVersion() == null || c.protocolVersion().isBlank()
            ? versions
                .current(protocol.id)
                .orElseThrow(
                    () ->
                        new ConflictException(
                            "protocolo sem versão vigente: " + protocol.id))
            : versions
                .find(protocol.id, c.protocolVersion().trim())
                .orElseThrow(
                    () -> new NotFoundException("versão de protocolo", c.protocolVersion()));
    CitizenDetail citizen = loadCitizen(c.citizenId());
    Map<String, Object> facts = facts(citizen);
    if (!RuleEvaluator.matches(objectMapper.valueToTree(v.eligibility), facts)) {
      throw new ConflictException("cidadão não elegível ao protocolo " + protocol.id);
    }
    if (plans.activeFor(citizen.id(), protocol.careLine).isPresent()) {
      throw new ConflictException("cidadão já possui plano ativo na linha " + protocol.careLine);
    }
    return toDto(instantiate(protocol, v, citizen, facts, c), true);
  }

  private CarePlan instantiate(
      Protocol protocol,
      ProtocolVersion v,
      CitizenDetail citizen,
      Map<String, Object> facts,
      CarePlanCreate c) {
    Instant now = Instant.now();
    Instant start = c.startAt() == null ? now : micros(c.startAt());
    CarePlan p = new CarePlan();
    p.id = Ulid.generate(Ulid.CARE_PLAN);
    p.tenantId = tenantContext.require();
    p.citizenId = citizen.id();
    p.careLine = protocol.careLine;
    p.status = "active";
    p.protocolId = protocol.id;
    p.protocolVersionId = v.id;
    p.protocolVersion = v.version;
    p.healthUnitCnes =
        Cnes.isValid(c.healthUnitCnes()) ? c.healthUnitCnes().trim() : citizen.healthUnitCnes();
    p.teamIne = blank(c.teamIne()) != null ? c.teamIne().trim() : citizen.teamIne();
    p.microarea = citizen.microarea();
    p.responsibleProfessionalId = blank(c.responsibleProfessionalId());
    if (c.origin() != null) {
      p.originKind = c.origin().kind();
      p.originId = c.origin().id();
    } else {
      p.originKind = "professional";
      p.originId = currentActor.actorId();
    }
    p.startAt = start;
    p.createdBy = currentActor.actorId();
    p.createdAt = now;
    p.updatedAt = now;
    plans.persist(p);

    List<CarePlanItem> created = new ArrayList<>();
    int seq = 0;
    for (ProtocolItemRule rule : rules(v)) {
      if (rule.condition() != null
          && !RuleEvaluator.matches(objectMapper.valueToTree(rule.condition()), facts)) {
        continue;
      }
      CarePlanItem i = newItem(p, rule, start.plus(Duration.ofDays(rule.dueInDays())), ++seq);
      items.persist(i);
      created.add(i);
    }
    events.publishPlan("created", p, created, null);
    LOG.infof("plano %s criado (%s v%s, %d itens)", p.id, protocol.id, v.version, created.size());
    return p;
  }

  private CarePlanItem newItem(CarePlan p, ProtocolItemRule rule, Instant expectedBy, int seq) {
    CarePlanItem i = new CarePlanItem();
    i.id = Ulid.generate(Ulid.CARE_PLAN_ITEM);
    i.tenantId = p.tenantId;
    i.carePlanId = p.id;
    i.citizenId = p.citizenId;
    i.sequence = seq;
    i.kind = rule.kind();
    i.title = rule.title();
    i.code = blank(rule.code());
    i.codeSystem = blank(rule.codeSystem());
    i.expectedBy = expectedBy;
    i.periodicityDays = rule.periodicityDays();
    i.gapAfterDays = rule.gapAfterDays() == null ? 0 : rule.gapAfterDays();
    i.priority = rule.priority() == null ? "medium" : rule.priority();
    i.status = "planned";
    return i;
  }

  @Override
  @TenantTransactional
  public CarePlanDto get(String carePlanId) {
    return toDto(loadPlan(carePlanId), true);
  }

  @Override
  @TenantTransactional
  public Page<CarePlanDto> list(
      String citizenId,
      String careLine,
      String status,
      String teamIne,
      String cnes,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    List<CarePlanDto> rows =
        plans
            .list(
                blank(citizenId),
                blank(careLine),
                blank(status),
                blank(teamIne),
                blank(cnes),
                Cursor.decode(cursor).orElse(null),
                size + 1)
            .stream()
            .map(p -> toDto(p, true))
            .toList();
    return Page.of(rows, size, CarePlanDto::id);
  }

  @Override
  @TenantTransactional
  public CarePlanDto updateItem(String carePlanId, String itemId, CarePlanItemUpdate u) {
    CarePlan p = loadPlan(carePlanId);
    CarePlanItem i = items.findById(itemId);
    if (i == null || !i.carePlanId.equals(p.id)) {
      throw new NotFoundException("item do plano", itemId);
    }
    if (!"active".equals(p.status)) {
      throw new ConflictException("plano não está ativo");
    }
    Instant now = Instant.now();
    if ("done".equals(u.status())) {
      markDone(
          p,
          i,
          u.performedAt() == null ? now : micros(u.performedAt()),
          blank(u.evidenceRef()),
          "performed");
    } else {
      i.status = u.status();
      if ("cancelled".equals(u.status())) {
        gaps.openByItem(i.id).ifPresent(g -> resolve(g, "cancelled", u.note()));
      }
    }
    i.note = blank(u.note());
    i.updatedAt = now;
    p.updatedAt = now;
    events.publishPlan("updated", p, items.byPlan(p.id), null);
    return toDto(p, true);
  }

  /** Marca item realizado, fecha a lacuna correspondente e gera a próxima ocorrência periódica. */
  private void markDone(
      CarePlan p, CarePlanItem i, Instant performedAt, String evidenceRef, String gapResolution) {
    i.status = "done";
    i.performedAt = performedAt;
    i.evidenceRef = evidenceRef;
    i.updatedAt = Instant.now();
    p.lastCareEventAt =
        p.lastCareEventAt == null || performedAt.isAfter(p.lastCareEventAt)
            ? performedAt
            : p.lastCareEventAt;
    gaps.openByItem(i.id).ifPresent(g -> resolve(g, gapResolution, null));
    if (i.periodicityDays != null && i.periodicityDays > 0) {
      ProtocolItemRule rule =
          new ProtocolItemRule(
              i.kind,
              i.title,
              i.code,
              i.codeSystem,
              i.periodicityDays,
              i.periodicityDays,
              i.gapAfterDays,
              i.priority,
              null);
      int seq = items.byPlan(p.id).stream().mapToInt(x -> x.sequence).max().orElse(0) + 1;
      items.persist(
          newItem(p, rule, performedAt.plus(Duration.ofDays(i.periodicityDays)), seq));
    }
  }

  @Override
  @TenantTransactional
  public CarePlanDto close(String carePlanId, CarePlanClose c) {
    CarePlan p = loadPlan(carePlanId);
    if (!"active".equals(p.status) && !"on_hold".equals(p.status)) {
      throw new ConflictException("plano já encerrado");
    }
    Instant now = Instant.now();
    p.status = c.status();
    p.closedReason = c.reason().trim();
    p.closedAt = now;
    p.updatedAt = now;
    for (CarePlanItem i : items.byPlan(p.id)) {
      if (i.isOpen()) {
        i.status = "cancelled";
        i.updatedAt = now;
      }
    }
    for (CareGap g : gaps.openByPlan(p.id)) {
      resolve(g, "cancelled", "plano encerrado: " + c.status());
    }
    events.publishPlan("closed", p, items.byPlan(p.id), null);
    return toDto(p, true);
  }

  @Override
  @TenantTransactional
  public Optional<String> openPlanForCareLine(
      String citizenId, String careLine, CarePlanOrigin origin) {
    if (careLine == null || careLine.isBlank()) {
      return Optional.empty();
    }
    String line = careLine.trim().toLowerCase();
    CitizenDetail citizen;
    try {
      citizen = loadCitizen(citizenId);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    if (plans.activeFor(citizen.id(), line).isPresent()) {
      return Optional.empty();
    }
    Map<String, Object> facts = facts(citizen);
    for (Protocol protocol : protocols.byCareLine(line)) {
      Optional<ProtocolVersion> v = versions.current(protocol.id);
      if (v.isEmpty()
          || !RuleEvaluator.matches(objectMapper.valueToTree(v.get().eligibility), facts)) {
        continue;
      }
      CarePlan p =
          instantiate(
              protocol,
              v.get(),
              citizen,
              facts,
              new CarePlanCreate(citizen.id(), protocol.id, null, null, null, null, null, origin));
      return Optional.of(p.id);
    }
    return Optional.empty();
  }

  @Override
  @TenantTransactional
  public List<String> activeCareLines(String citizenId) {
    return plans.activeCareLines(citizenId);
  }

  // ---------------------------------------------------------------------
  // lacunas (CUI-003/004/006)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public Page<CareGapDto> listGaps(
      String careLine,
      CareGapKind kind,
      String cnes,
      String teamIne,
      String microarea,
      String status,
      Integer minDaysOverdue,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    Instant expectedBefore =
        minDaysOverdue == null ? null : Instant.now().minus(Duration.ofDays(minDaysOverdue));
    List<CareGapDto> rows =
        gaps
            .list(
                blank(careLine),
                kind == null ? null : kind.wire(),
                blank(cnes),
                blank(teamIne),
                blank(microarea),
                blank(status) == null ? "open" : status.trim(),
                expectedBefore,
                Cursor.decode(cursor).orElse(null),
                size + 1)
            .stream()
            .map(g -> toDto(g, true))
            .toList();
    return Page.of(rows, size, CareGapDto::id);
  }

  @Override
  @TenantTransactional
  public CareGapDto resolveGap(String careGapId, CareGapResolve r) {
    CareGap g = gaps.findById(careGapId);
    if (g == null) {
      throw new NotFoundException("lacuna de cuidado", careGapId);
    }
    if (!"open".equals(g.status)) {
      throw new ConflictException("lacuna já resolvida");
    }
    if ("performed".equals(r.resolution()) && g.itemId != null) {
      CarePlanItem i = items.findById(g.itemId);
      CarePlan p = i == null ? null : plans.findById(i.carePlanId);
      if (i != null && p != null && i.isOpen()) {
        markDone(p, i, Instant.now(), null, r.resolution());
        p.updatedAt = Instant.now();
        events.publishPlan("updated", p, items.byPlan(p.id), null);
        return toDto(g, true);
      }
    }
    resolve(g, r.resolution(), r.note());
    return toDto(g, true);
  }

  private void resolve(CareGap g, String resolution, String note) {
    if (!"open".equals(g.status)) {
      return;
    }
    Instant now = Instant.now();
    g.status = "resolved";
    g.resolution = resolution;
    g.note = blank(note);
    g.resolvedAt = now;
    g.updatedAt = now;
    taskCommands.completeByOrigin("rule", GAP_ORIGIN_PREFIX + g.id, resolution, note);
    events.publishGap("resolved", g, null);
  }

  @Override
  @TenantTransactional
  public Optional<String> openPostDischargeNoContactGap(
      String citizenId,
      String careLine,
      String episodeId,
      OffsetDateTime expectedBy,
      String healthUnitCnes,
      String teamIne,
      String microarea) {
    String kind = CareGapKind.POST_DISCHARGE_NO_CONTACT.wire();
    Optional<CareGap> existing = gaps.openByOrigin(episodeId, kind);
    if (existing.isPresent()) {
      return existing.map(g -> g.id);
    }
    CareGap g = new CareGap();
    g.id = Ulid.generate(Ulid.CARE_GAP);
    g.tenantId = tenantContext.require();
    g.citizenId = citizenId;
    g.careLine = blank(careLine) == null ? "pos_alta" : careLine.trim().toLowerCase();
    g.gapKind = kind;
    g.status = "open";
    g.expectedBy = expectedBy == null ? null : micros(expectedBy);
    g.protocolVersion = "post-discharge-followup/1";
    g.healthUnitCnes = Cnes.isValid(healthUnitCnes) ? healthUnitCnes : null;
    g.teamIne = blank(teamIne);
    g.microarea = blank(microarea);
    g.originRef = episodeId;
    g.detectedAt = Instant.now();
    gaps.persist(g);
    g.taskId = openGapTask(g, "high", "Pós-alta sem contato — busca ativa").id();
    events.publishGap("detected", g, null);
    return Optional.of(g.id);
  }

  @Override
  @TenantTransactional
  public int resolveGapsByOrigin(String originRef, String resolution, String note) {
    int n = 0;
    for (CareGap g : gaps.openByOrigin(originRef)) {
      resolve(g, resolution, note);
      n++;
    }
    return n;
  }

  @Override
  @TenantTransactional
  public long countOpenGaps(String citizenId) {
    return gaps.countOpen(citizenId);
  }

  // ---------------------------------------------------------------------
  // evidência automática (CUI-002)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public int applyAppointmentEvidence(
      String citizenId,
      String appointmentId,
      String serviceCode,
      boolean exam,
      OffsetDateTime occurredAt) {
    return applyEvidence(
        citizenId,
        exam ? Set.of("exam") : CONSULTATION_KINDS,
        serviceCode,
        occurredAt == null ? Instant.now() : micros(occurredAt),
        "/api/v1/appointments/" + appointmentId);
  }

  @Override
  @TenantTransactional
  public int applyExamEvidence(
      String citizenId, String examOrderId, String examCode, OffsetDateTime occurredAt) {
    return applyEvidence(
        citizenId,
        Set.of("exam"),
        examCode,
        occurredAt == null ? Instant.now() : micros(occurredAt),
        "/api/v1/exams/orders/" + examOrderId);
  }

  private int applyEvidence(
      String citizenId, Set<String> kinds, String code, Instant occurredAt, String evidenceRef) {
    if (citizenId == null) {
      return 0;
    }
    int matched = 0;
    for (CarePlan p : plans.activeByCitizen(citizenId)) {
      if (p.lastCareEventAt == null || occurredAt.isAfter(p.lastCareEventAt)) {
        p.lastCareEventAt = occurredAt;
      }
      List<CarePlanItem> open = items.byPlan(p.id).stream().filter(CarePlanItem::isOpen).toList();
      Optional<CarePlanItem> byCode =
          code == null
              ? Optional.empty()
              : open.stream()
                  .filter(i -> code.trim().equals(i.code))
                  .min(Comparator.comparing(i -> i.expectedBy, nullsLast()));
      Optional<CarePlanItem> target =
          byCode.isPresent()
              ? byCode
              : open.stream()
                  .filter(i -> kinds.contains(i.kind))
                  .filter(i -> i.expectedBy != null && within(i.expectedBy, occurredAt))
                  .min(Comparator.comparing(i -> i.expectedBy, nullsLast()));
      if (target.isPresent()) {
        markDone(p, target.get(), occurredAt, evidenceRef, "performed");
        p.updatedAt = Instant.now();
        events.publishPlan("updated", p, items.byPlan(p.id), null);
        matched++;
      }
    }
    return matched;
  }

  private static boolean within(Instant expectedBy, Instant occurredAt) {
    return Math.abs(Duration.between(expectedBy, occurredAt).toMillis()) <= EVIDENCE_WINDOW.toMillis();
  }

  private static Comparator<Instant> nullsLast() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }

  // ---------------------------------------------------------------------
  // detecção de lacunas (CUI-003) — CareGapDetectionWorkflow/job
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public int detectGaps() {
    Instant now = Instant.now();
    int opened = 0;
    for (CarePlanItem i : items.overdue(now)) {
      if (!i.expectedBy.plus(Duration.ofDays(i.gapAfterDays)).isBefore(now)) {
        continue;
      }
      CarePlan p = plans.findById(i.carePlanId);
      if (p == null || !"active".equals(p.status) || gaps.openByItem(i.id).isPresent()) {
        continue;
      }
      i.status = "missed";
      i.updatedAt = now;
      CareGap g = newGap(p, i, CareGapKind.forItemKind(i.kind), i.expectedBy, now);
      gaps.persist(g);
      g.taskId = openGapTask(g, i.priority, "Lacuna de cuidado: " + i.title).id();
      events.publishGap("detected", g, null);
      opened++;
    }
    for (CarePlan p : plans.active()) {
      ProtocolVersion v = versions.findById(p.protocolVersionId);
      int lostDays = v == null ? 90 : v.lostToFollowupDays;
      Instant last = p.lastCareEventAt == null ? p.startAt : p.lastCareEventAt;
      if (!last.plus(Duration.ofDays(lostDays)).isBefore(now)
          || gaps.openByPlanKind(p.id, CareGapKind.LOST_TO_FOLLOWUP.wire()).isPresent()) {
        continue;
      }
      CareGap g = newGap(p, null, CareGapKind.LOST_TO_FOLLOWUP, last.plus(Duration.ofDays(lostDays)), now);
      gaps.persist(g);
      g.taskId = openGapTask(g, "high", "Perda de seguimento na linha " + p.careLine).id();
      events.publishGap("detected", g, null);
      opened++;
    }
    if (opened > 0) {
      LOG.infof("detecção de lacunas: %d lacuna(s) aberta(s)", opened);
    }
    return opened;
  }

  private CareGap newGap(
      CarePlan p, CarePlanItem i, CareGapKind kind, Instant expectedBy, Instant now) {
    CareGap g = new CareGap();
    g.id = Ulid.generate(Ulid.CARE_GAP);
    g.tenantId = p.tenantId;
    g.citizenId = p.citizenId;
    g.carePlanId = p.id;
    g.itemId = i == null ? null : i.id;
    g.careLine = p.careLine;
    g.gapKind = kind.wire();
    g.status = "open";
    g.expectedBy = expectedBy;
    g.protocolId = p.protocolId;
    g.protocolVersion = p.protocolVersion;
    g.healthUnitCnes = p.healthUnitCnes;
    g.teamIne = p.teamIne;
    g.microarea = p.microarea;
    g.detectedAt = now;
    return g;
  }

  /** Tarefa {@code care_gap} para equipe → UBS → fila {@code busca_ativa}; sem PII na descrição. */
  private TaskDto openGapTask(CareGap g, String priority, String title) {
    Optional<TaskDto> existing = taskQueries.findOpenByOrigin("rule", GAP_ORIGIN_PREFIX + g.id);
    if (existing.isPresent()) {
      return existing.get();
    }
    Assignee assignee =
        g.teamIne != null
            ? Assignee.team(g.teamIne)
            : (Cnes.isValid(g.healthUnitCnes)
                ? Assignee.healthUnit(g.healthUnitCnes)
                : Assignee.queue("busca_ativa"));
    return taskCommands.create(
        new TaskCreate(
            TaskType.CARE_GAP,
            TaskPriority.fromWire(priority == null ? "medium" : priority),
            title.length() > 200 ? title.substring(0, 200) : title,
            "Lacuna "
                + g.gapKind
                + " na linha de cuidado "
                + g.careLine
                + (g.expectedBy == null ? "" : " (previsto para " + g.expectedBy.atOffset(ZoneOffset.UTC) + ")")
                + ". Realizar busca ativa e registrar o desfecho em /api/v1/caregaps/"
                + g.id
                + "/resolve.",
            g.citizenId,
            assignee,
            null,
            null,
            TaskOrigin.rule(GAP_ORIGIN_PREFIX + g.id, g.protocolVersion)),
        null);
  }

  // ---------------------------------------------------------------------

  /** Fatos permitidos ao avaliador de elegibilidade: idade e sexo. */
  static Map<String, Object> facts(CitizenDetail c) {
    Map<String, Object> facts = new LinkedHashMap<>();
    if (c.birthdate() != null) {
      facts.put("age_years", Period.between(c.birthdate(), LocalDate.now()).getYears());
    }
    facts.put("sex", c.sex() == null ? "unknown" : c.sex().wire());
    return facts;
  }

  /** Rejeita operadores desconhecidos na criação (fail-fast; o avaliador também rejeita). */
  private static void validateCondition(com.fasterxml.jackson.databind.JsonNode node) {
    try {
      RuleEvaluator.matches(node, Map.of("age_years", 0, "sex", "unknown"));
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.field("condition", e.getMessage());
    }
  }

  private List<ProtocolItemRule> rules(ProtocolVersion v) {
    return objectMapper.convertValue(v.items, new TypeReference<List<ProtocolItemRule>>() {});
  }

  private static int parseVersion(String v) {
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private CitizenDetail loadCitizen(String citizenId) {
    CitizenDetail c;
    try {
      c = citizens.get(citizenId);
    } catch (NotFoundException e) {
      throw DomainValidationException.field("citizen_id", "cidadão não encontrado");
    }
    int hops = 0;
    while (c.mergedIntoId() != null && hops++ < 10) {
      c = citizens.get(c.mergedIntoId());
    }
    return c;
  }

  private Protocol loadProtocol(String id) {
    Protocol p = protocols.findById(id);
    if (p == null) {
      throw new NotFoundException("protocolo", id);
    }
    return p;
  }

  private CarePlan loadPlan(String id) {
    CarePlan p = plans.findById(id);
    if (p == null) {
      throw new NotFoundException("plano de cuidado", id);
    }
    return p;
  }

  static int daysOverdue(Instant expectedBy, Instant now) {
    if (expectedBy == null || !expectedBy.isBefore(now)) {
      return 0;
    }
    return (int) Duration.between(expectedBy, now).toDays();
  }

  ProtocolDto toDto(Protocol p, ProtocolVersion v) {
    return new ProtocolDto(
        p.id,
        p.careLine,
        p.name,
        v.version,
        v.status,
        v.description,
        v.eligibility,
        rules(v),
        v.lostToFollowupDays,
        v.testCases == null ? 0 : v.testCases.size(),
        v.approvedBy,
        offset(v.effectiveFrom),
        offset(v.createdAt),
        v.tenantId == null);
  }

  CarePlanDto toDto(CarePlan p, boolean withItems) {
    Instant now = Instant.now();
    List<CarePlanItemDto> itemDtos =
        withItems
            ? items.byPlan(p.id).stream()
                .map(
                    i ->
                        new CarePlanItemDto(
                            i.id,
                            i.kind,
                            i.title,
                            i.code,
                            i.codeSystem,
                            offset(i.expectedBy),
                            i.periodicityDays,
                            i.status,
                            offset(i.performedAt),
                            i.evidenceRef,
                            i.isOpen() && i.expectedBy != null && i.expectedBy.isBefore(now)))
                .toList()
            : null;
    return new CarePlanDto(
        p.id,
        p.citizenId,
        p.careLine,
        p.status,
        p.protocolId,
        p.protocolVersion,
        p.healthUnitCnes,
        p.teamIne,
        p.responsibleProfessionalId,
        p.originKind == null ? null : new CarePlanOrigin(p.originKind, p.originId),
        itemDtos,
        gaps.openByPlan(p.id).size(),
        offset(p.startAt),
        offset(p.createdAt),
        offset(p.updatedAt),
        p.closedReason,
        p.version);
  }

  CareGapDto toDto(CareGap g, boolean enrich) {
    String displayName = null;
    Boolean contactValid = null;
    if (enrich) {
      try {
        displayName = citizens.get(g.citizenId).displayName();
      } catch (RuntimeException e) {
        displayName = null;
      }
      contactValid = consent.contactValid(g.citizenId);
    }
    return new CareGapDto(
        g.id,
        g.citizenId,
        displayName,
        g.carePlanId,
        g.careLine,
        CareGapKind.fromWire(g.gapKind),
        g.status,
        offset(g.expectedBy),
        daysOverdue(g.expectedBy, g.resolvedAt == null ? Instant.now() : g.resolvedAt),
        g.protocolId,
        g.protocolVersion,
        g.healthUnitCnes,
        g.teamIne,
        g.microarea,
        g.taskId,
        offset(g.detectedAt),
        offset(g.resolvedAt),
        g.resolution,
        contactValid);
  }

  private static Instant micros(OffsetDateTime t) {
    return t == null ? null : t.toInstant().truncatedTo(ChronoUnit.MICROS);
  }

  private static OffsetDateTime offset(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  @SuppressWarnings("unused")
  private static boolean same(Object a, Object b) {
    return Objects.equals(a, b);
  }
}
