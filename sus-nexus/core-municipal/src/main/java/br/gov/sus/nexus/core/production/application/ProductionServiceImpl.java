package br.gov.sus.nexus.core.production.application;

import br.gov.sus.nexus.core.audit.api.AuditEntry;
import br.gov.sus.nexus.core.audit.api.AuditService;
import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.CitizenService;
import br.gov.sus.nexus.core.identity.api.CitizenSummary;
import br.gov.sus.nexus.core.identity.api.IdentifierSystem;
import br.gov.sus.nexus.core.identity.api.Sex;
import br.gov.sus.nexus.core.platform.errors.ConflictException;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.errors.ProblemException;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.security.FieldCipher;
import br.gov.sus.nexus.core.platform.security.Roles;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.production.api.ExternalRef;
import br.gov.sus.nexus.core.production.api.ProductionBatchApproval;
import br.gov.sus.nexus.core.production.api.ProductionBatchCreate;
import br.gov.sus.nexus.core.production.api.ProductionBatchDto;
import br.gov.sus.nexus.core.production.api.ProductionBatchExportRequest;
import br.gov.sus.nexus.core.production.api.ProductionCorrection;
import br.gov.sus.nexus.core.production.api.ProductionDeadlineDto;
import br.gov.sus.nexus.core.production.api.ProductionIssueDto;
import br.gov.sus.nexus.core.production.api.ProductionKind;
import br.gov.sus.nexus.core.production.api.ProductionOutcomeRegistration;
import br.gov.sus.nexus.core.production.api.ProductionOutcomeResult;
import br.gov.sus.nexus.core.production.api.ProductionRecordDto;
import br.gov.sus.nexus.core.production.api.ProductionRecordRegistration;
import br.gov.sus.nexus.core.production.api.ProductionRecordResult;
import br.gov.sus.nexus.core.production.api.ProductionRecordStatus;
import br.gov.sus.nexus.core.production.api.ProductionService;
import br.gov.sus.nexus.core.production.api.ProductionSummary;
import br.gov.sus.nexus.core.production.domain.ProductionBatch;
import br.gov.sus.nexus.core.production.domain.ProductionBatchItem;
import br.gov.sus.nexus.core.production.domain.ProductionOutcome;
import br.gov.sus.nexus.core.production.domain.ProductionRecord;
import br.gov.sus.nexus.core.production.domain.ProductionRecordHistory;
import br.gov.sus.nexus.core.production.domain.ProductionSourceLink;
import br.gov.sus.nexus.core.production.domain.ProductionSubmission;
import br.gov.sus.nexus.core.production.domain.ProductionValidationIssue;
import br.gov.sus.nexus.core.production.infrastructure.ProductionRepositories;
import br.gov.sus.nexus.core.production.infrastructure.temporal.ProductionWorkflowStarter;
import br.gov.sus.nexus.core.reference.api.HealthUnitDto;
import br.gov.sus.nexus.core.reference.api.HealthUnitService;
import br.gov.sus.nexus.core.sharedkernel.Cns;
import br.gov.sus.nexus.core.sharedkernel.Competence;
import br.gov.sus.nexus.core.sharedkernel.Cpf;
import br.gov.sus.nexus.core.sharedkernel.IdentifierHash;
import br.gov.sus.nexus.core.sharedkernel.Masks;
import br.gov.sus.nexus.core.tasks.api.Assignee;
import br.gov.sus.nexus.core.tasks.api.SlaPolicyDto;
import br.gov.sus.nexus.core.tasks.api.TaskCommands;
import br.gov.sus.nexus.core.tasks.api.TaskCreate;
import br.gov.sus.nexus.core.tasks.api.TaskDto;
import br.gov.sus.nexus.core.tasks.api.TaskOrigin;
import br.gov.sus.nexus.core.tasks.api.TaskPriority;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import br.gov.sus.nexus.core.tasks.api.TaskType;
import br.gov.sus.nexus.core.terminology.api.CodeDto;
import br.gov.sus.nexus.core.terminology.api.TerminologyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Produção ambulatorial/hospitalar (PRO-001..010, Workflow 3): registro por vínculo de origem com
 * pré-auditoria por regras versionadas ({@link PreAuditor}), pendências com {@code rule_version} e
 * tarefa {@code production_issue} na fila {@code auditoria}, correção humana com justificativa
 * (PRO-006), lotes só com registros validados e aprovação humana obrigatória (PRO-010), exportação
 * em layout de referência ({@link ExportLayouts}) via {@link ExportStorage}, retornos oficiais
 * (PRO-008), painel (PRO-009) e prazos por competência (PRO-007). Agentes de IA nunca corrigem,
 * geram/aprovam/exportam lote nem registram retorno.
 */
@ApplicationScoped
public class ProductionServiceImpl implements ProductionService {

  private static final Logger LOG = Logger.getLogger(ProductionServiceImpl.class);

  public static final String PREAUDIT_ORIGIN_PREFIX = "production-preaudit:";
  public static final String DEADLINE_ORIGIN_PREFIX = "production-deadline:";
  public static final String AUDIT_QUEUE = "auditoria";
  static final String DEADLINE_MISSED = "deadline_missed";
  static final String OFFICIAL_REJECTION = "official_rejection";
  static final Set<String> CORRECTABLE = Set.of("pending", "validated", "rejected", "corrected");

  @Inject ProductionRepositories.Records records;
  @Inject ProductionRepositories.History history;
  @Inject ProductionRepositories.Issues issues;
  @Inject ProductionRepositories.Batches batches;
  @Inject ProductionRepositories.BatchItems batchItems;
  @Inject ProductionRepositories.Outcomes outcomes;
  @Inject ProductionRepositories.SourceLinks links;
  @Inject ProductionEvents events;
  @Inject PreAuditor preAuditor;
  @Inject ProductionDeadlines deadlines;
  @Inject ExportStorage storage;
  @Inject CitizenService citizens;
  @Inject HealthUnitService healthUnits;
  @Inject TerminologyService terminology;
  @Inject TaskCommands taskCommands;
  @Inject TaskQueries taskQueries;
  @Inject AuditService audit;
  @Inject ProductionWorkflowStarter starter;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;
  @Inject IdentifierHash identifierHash;
  @Inject FieldCipher cipher;
  @Inject EntityManager entityManager;
  @Inject ObjectMapper objectMapper;

  @ConfigProperty(name = "sus.production.export-origin-name", defaultValue = "SECRETARIA MUNICIPAL")
  String exportOriginName;

  @ConfigProperty(name = "sus.production.export-origin-acronym", defaultValue = "SMS")
  String exportOriginAcronym;

  @ConfigProperty(name = "sus.production.export-origin-document", defaultValue = "00000000000000")
  String exportOriginDocument;

  // ---------------------------------------------------------------------
  // registro + pré-auditoria (PRO-001..004)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ProductionRecordResult register(ProductionRecordRegistration reg) {
    String tenant = tenantContext.require();
    validateInput(reg);
    String payloadHash = payloadHash(reg);
    Optional<ProductionSourceLink> link =
        links.findBySource(tenant, reg.source().system(), reg.source().sourceRecordId());
    Instant now = Instant.now();

    if (link.isEmpty()) {
      ProductionRecord r = new ProductionRecord();
      r.id = Ulid.generate(Ulid.PRODUCTION_RECORD);
      r.tenantId = tenant;
      r.status = ProductionRecordStatus.GENERATED.wire();
      r.sourceSystem = reg.source().system();
      r.sourceRecordId = reg.source().sourceRecordId();
      r.sourceRecordVersion = reg.source().sourceRecordVersion();
      r.payloadHash = payloadHash;
      r.createdAt = ProductionRepositories.micros(now);
      r.updatedAt = r.createdAt;
      copy(reg, r);
      CitizenDetail citizen = resolveCitizen(r, reg.citizenRef());
      records.persist(r);

      ProductionSourceLink l = new ProductionSourceLink();
      l.id = Ulid.generate(Ulid.SOURCE_LINK);
      l.tenantId = tenant;
      l.recordId = r.id;
      l.sourceSystem = reg.source().system();
      l.connector = reg.source().connector();
      l.sourceRecordId = reg.source().sourceRecordId();
      l.sourceRecordVersion = reg.source().sourceRecordVersion();
      links.persist(l);

      addHistory(r, "created", null, r.status, Map.of(), null);
      events.publishRecord("created", r, 0, 0, null);
      preAudit(r, citizen, true);
      LOG.infof("produção %s (%s %s) registrada: %s", r.id, r.kind, r.competence, r.status);
      return new ProductionRecordResult(toDto(r, false), true);
    }

    ProductionRecord r = load(link.get().recordId);
    if (Objects.equals(r.payloadHash, payloadHash)) {
      return new ProductionRecordResult(toDto(r, false), false);
    }
    ProductionRecordStatus status = ProductionRecordStatus.fromWire(r.status);
    if (status.submitted()) {
      throw new ConflictException(
          "registro de produção já exportado/processado ("
              + r.status
              + "): corrigir no sistema oficial ou reapresentar como novo registro");
    }
    detachFromDraftBatch(r);
    Map<String, Object> changes = diff(r, reg);
    copy(reg, r);
    CitizenDetail citizen = resolveCitizen(r, reg.citizenRef());
    r.payloadHash = payloadHash;
    r.sourceRecordVersion = reg.source().sourceRecordVersion();
    r.updatedAt = now;
    link.get().sourceRecordVersion = reg.source().sourceRecordVersion();
    link.get().updatedAt = now;
    addHistory(r, "updated", r.status, r.status, changes, null);
    preAudit(r, citizen, false);
    starter.signalCorrectedAfterCommit(r.id);
    return new ProductionRecordResult(toDto(r, false), false);
  }

  private void validateInput(ProductionRecordRegistration reg) {
    if (!Competence.isValid(reg.competence())) {
      throw DomainValidationException.field("competence", "competência inválida (AAAAMM)");
    }
    if (present(reg.professionalCns()) && !Cns.isValid(reg.professionalCns())) {
      throw DomainValidationException.field("professional_cns", "CNS do profissional inválido");
    }
    validateCitizenRef(reg.citizenRef(), "citizen_ref");
  }

  private static void validateCitizenRef(ProductionRecordRegistration.CitizenRef ref, String f) {
    if (ref != null
        && present(ref.identifierValue())
        && ref.identifierSystem() != IdentifierSystem.CNS
        && ref.identifierSystem() != IdentifierSystem.CPF) {
      throw DomainValidationException.field(f + ".identifier_system", "use CNS ou CPF");
    }
  }

  /** Copia os campos (identificadores do profissional: hash + máscara + cifra). */
  private void copy(ProductionRecordRegistration reg, ProductionRecord r) {
    r.kind = reg.kind().wire();
    r.competence = reg.competence();
    r.cnes = reg.cnes().trim();
    r.professionalCbo = reg.professionalCbo().trim();
    r.procedureCode = reg.procedureCode().trim();
    r.quantity = reg.quantity();
    r.cidCode = blank(reg.cidCode()) == null ? null : reg.cidCode().trim().toUpperCase();
    r.attendanceDate = reg.attendanceDate();
    r.characterOfCare = blank(reg.characterOfCare());
    r.apacNumber = blank(reg.apacNumber());
    r.aihNumber = blank(reg.aihNumber());
    setProfessionalCns(r, reg.professionalCns());
    setRefs(r, reg.encounterRef(), reg.appointmentRef(), reg.hospitalEpisodeRef());
  }

  private void setProfessionalCns(ProductionRecord r, String cns) {
    if (!present(cns)) {
      r.professionalCnsHash = null;
      r.professionalCnsMasked = null;
      r.professionalCnsEnc = null;
      return;
    }
    String digits = digits(cns);
    r.professionalCnsHash = identifierHash.hash(r.tenantId, "CNS", digits);
    r.professionalCnsMasked = Masks.cns(digits);
    r.professionalCnsEnc = cipher.encrypt(digits);
  }

  private static void setRefs(
      ProductionRecord r, ExternalRef encounter, ExternalRef appointment, ExternalRef episode) {
    if (encounter != null) {
      r.encounterSourceSystem = blank(encounter.system());
      r.encounterSourceRecordId = blank(encounter.sourceRecordId());
    }
    if (appointment != null) {
      r.appointmentSourceSystem = blank(appointment.system());
      r.appointmentSourceRecordId = blank(appointment.sourceRecordId());
    }
    if (episode != null) {
      r.hospitalEpisodeSourceSystem = blank(episode.system());
      r.hospitalEpisodeSourceRecordId = blank(episode.sourceRecordId());
    }
  }

  /**
   * Resolve o cidadão (municipal_citizen_id ou CNS/CPF via MPI). CNS/CPF informado: DV validado
   * (fato de entrada), guardado como hash + máscara + cifra. Não localizado → {@code citizen_id}
   * nulo (pendência {@code citizen_unresolved} nos individualizados).
   */
  private CitizenDetail resolveCitizen(
      ProductionRecord r, ProductionRecordRegistration.CitizenRef ref) {
    Map<String, Object> facts = new LinkedHashMap<>();
    if (r.facts != null) {
      facts.putAll(r.facts);
    }
    facts.remove(PreAuditor.INPUT_IDENTIFIER_VALID);
    r.facts = facts;
    r.citizenId = null;
    r.citizenIdentifierSystem = null;
    r.citizenIdentifierHash = null;
    r.citizenIdentifierMasked = null;
    r.citizenIdentifierEnc = null;
    if (ref == null || !ref.present()) {
      return null;
    }
    CitizenDetail citizen = null;
    if (present(ref.municipalCitizenId())) {
      citizen = safeCitizen(ref.municipalCitizenId().trim());
    }
    if (present(ref.identifierValue())) {
      String system = ref.identifierSystem().name();
      String digits = digits(ref.identifierValue());
      boolean valid = "CNS".equals(system) ? Cns.isValid(digits) : Cpf.isValid(digits);
      facts.put(PreAuditor.INPUT_IDENTIFIER_VALID, valid);
      r.citizenIdentifierSystem = system;
      r.citizenIdentifierMasked = Masks.forSystem(system, digits);
      if (valid) {
        r.citizenIdentifierHash = identifierHash.hash(r.tenantId, system, digits);
        r.citizenIdentifierEnc = cipher.encrypt(digits);
        if (citizen == null) {
          Page<CitizenSummary> found =
              citizens.search(null, system + "|" + digits, null, null, null, 1);
          if (!found.items().isEmpty()) {
            citizen = safeCitizen(found.items().get(0).id());
          }
        }
      }
    }
    r.citizenId = citizen == null ? null : citizen.id();
    return citizen;
  }

  private CitizenDetail safeCitizen(String id) {
    try {
      CitizenDetail c = citizens.get(id);
      int hops = 0;
      while (c.mergedIntoId() != null && hops++ < 10) {
        c = citizens.get(c.mergedIntoId());
      }
      return c;
    } catch (NotFoundException e) {
      return null;
    }
  }

  /**
   * Pré-auditoria idempotente: avalia as regras vigentes, sincroniza as pendências de origem {@code
   * rule} (novas → abertas; não mais violadas → resolvidas; avisos dispensados permanecem), decide
   * o status (erro aberto → {@code pending}; senão {@code validated}) e mantém a tarefa da fila
   * {@code auditoria}.
   */
  private void preAudit(ProductionRecord r, CitizenDetail citizen, boolean always) {
    Instant now = Instant.now();
    PreAuditor.Result res = preAuditor.evaluate(r, citizen, now);
    r.ruleVersion = res.ruleVersion();
    r.facts = new LinkedHashMap<>(res.facts());
    r.unitValue = res.unitValue();
    r.estimatedValue =
        res.unitValue() == null
            ? null
            : res.unitValue()
                .multiply(BigDecimal.valueOf(r.quantity))
                .setScale(2, RoundingMode.HALF_UP);
    if (res.appointmentId() != null) {
      r.appointmentId = res.appointmentId();
    }
    if (res.hospitalEpisodeId() != null) {
      r.hospitalEpisodeId = res.hospitalEpisodeId();
    }
    r.deadlineAt = deadlines.resolve(r.competence).deadlineAt();
    r.validatedAt = now;
    r.updatedAt = now;

    List<ProductionValidationIssue> all = issues.byRecord(r.id);
    Map<String, ProductionValidationIssue> openRule = new HashMap<>();
    Set<String> waived = new java.util.HashSet<>();
    for (ProductionValidationIssue i : all) {
      if ("rule".equals(i.origin) && i.open()) {
        openRule.put(i.ruleId, i);
      }
      if ("waived".equals(i.status)) {
        waived.add(i.ruleId);
      }
    }
    Set<String> found = new java.util.HashSet<>();
    for (PreAuditor.Finding f : res.findings()) {
      found.add(f.ruleId());
      ProductionValidationIssue existing = openRule.get(f.ruleId());
      if (existing != null) {
        existing.ruleVersion = res.ruleVersion();
        existing.message = f.message();
        existing.severity = f.severity();
        continue;
      }
      if ("warning".equals(f.severity()) && waived.contains(f.ruleId())) {
        continue; // aviso dispensado por humano com justificativa
      }
      ProductionValidationIssue i = new ProductionValidationIssue();
      i.id = Ulid.generate(Ulid.PRODUCTION_ISSUE);
      i.tenantId = r.tenantId;
      i.recordId = r.id;
      i.ruleId = f.ruleId();
      i.ruleVersion = res.ruleVersion();
      i.severity = f.severity();
      i.field = f.field();
      i.message = f.message();
      i.status = "open";
      i.origin = "rule";
      i.createdAt = now;
      issues.persist(i);
      events.publishIssue("issue_found", i, r);
    }
    for (ProductionValidationIssue i : openRule.values()) {
      if (!found.contains(i.ruleId)) {
        resolveIssue(
            i, "resolved", "revalidação: regra não mais violada (" + res.ruleVersion() + ")");
        events.publishIssue("issue_resolved", i, r);
      }
    }
    applyStatus(r, always);
  }

  /** Status pelo conjunto de pendências abertas (qualquer origem) + tarefa/eventos. */
  private void applyStatus(ProductionRecord r, boolean always) {
    List<ProductionValidationIssue> open = issues.openByRecord(r.id);
    int errors = (int) open.stream().filter(ProductionValidationIssue::error).count();
    int warnings = open.size() - errors;
    String previous = r.status;
    String next =
        (errors > 0 ? ProductionRecordStatus.PENDING : ProductionRecordStatus.VALIDATED).wire();
    r.status = next;
    boolean changed = !next.equals(previous);
    if (changed) {
      addHistory(r, next, previous, next, Map.of(), null);
    }
    if (changed || always) {
      events.publishRecord(next, r, errors, warnings, null);
    }
    if (errors > 0) {
      String taskId = ensureTask(r, open, errors);
      for (ProductionValidationIssue i : open) {
        if (i.taskId == null) {
          i.taskId = taskId;
        }
      }
    } else {
      taskCommands.completeByOrigin(
          "rule", PREAUDIT_ORIGIN_PREFIX + r.id, "validated", "registro validado");
    }
  }

  /** Tarefa {@code production_issue} (fila auditoria; sem cidadão: não vai para a timeline). */
  private String ensureTask(ProductionRecord r, List<ProductionValidationIssue> open, int errors) {
    String originId = PREAUDIT_ORIGIN_PREFIX + r.id;
    Optional<TaskDto> existing = taskQueries.findOpenByOrigin("rule", originId);
    if (existing.isPresent()) {
      return existing.get().id();
    }
    Instant now = Instant.now();
    boolean nearDeadline =
        r.deadlineAt != null && Duration.between(now, r.deadlineAt).toDays() <= 5;
    boolean urgentRule =
        open.stream()
            .anyMatch(i -> DEADLINE_MISSED.equals(i.ruleId) || OFFICIAL_REJECTION.equals(i.ruleId));
    TaskPriority priority = urgentRule || nearDeadline ? TaskPriority.URGENT : TaskPriority.HIGH;
    Optional<SlaPolicyDto> policy = taskQueries.slaPolicy(TaskType.PRODUCTION_ISSUE, priority);
    Instant due = now.plus(policy.map(SlaPolicyDto::dueIn).orElse(Duration.ofDays(10)));
    if (r.deadlineAt != null && r.deadlineAt.isAfter(now) && r.deadlineAt.isBefore(due)) {
      due = r.deadlineAt;
    }
    StringBuilder rules = new StringBuilder();
    open.stream()
        .filter(ProductionValidationIssue::error)
        .forEach(i -> rules.append(rules.isEmpty() ? "" : ", ").append(i.ruleId));
    TaskDto task =
        taskCommands.create(
            new TaskCreate(
                TaskType.PRODUCTION_ISSUE,
                priority,
                truncate(
                    "Pendência de produção "
                        + ProductionKind.fromWire(r.kind).instrument()
                        + " "
                        + r.competence
                        + " — CNES "
                        + r.cnes,
                    200),
                truncate(
                    "Registro "
                        + r.id
                        + " (procedimento "
                        + r.procedureCode
                        + ") com "
                        + errors
                        + " erro(s) de pré-auditoria ["
                        + rules
                        + "] pela regra "
                        + r.ruleVersion
                        + ". Corrigir com justificativa em /api/v1/production/records/"
                        + r.id
                        + "/corrections até o prazo da competência.",
                    2000),
                null,
                Assignee.queue(AUDIT_QUEUE),
                due.atOffset(ZoneOffset.UTC),
                policy.map(SlaPolicyDto::id).orElse(null),
                TaskOrigin.rule(originId, r.ruleVersion)),
            null);
    return task.id();
  }

  private void resolveIssue(ProductionValidationIssue i, String status, String note) {
    i.status = status;
    i.resolvedAt = Instant.now();
    i.resolvedBy = currentActor.actorId();
    i.resolutionNote = truncate(note, 1000);
  }

  // ---------------------------------------------------------------------
  // correção humana (PRO-006)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ProductionRecordDto correct(String recordId, ProductionCorrection c) {
    rejectAgents("corrigir registro de produção");
    ProductionRecord r = load(recordId);
    if (!CORRECTABLE.contains(r.status)) {
      throw new ConflictException(
          "registro em " + r.status + " não pode ser corrigido no barramento");
    }
    if (c.justification() == null || c.justification().trim().length() < 10) {
      throw DomainValidationException.field("justification", "justificativa obrigatória");
    }
    String justification = c.justification().trim();
    detachFromDraftBatch(r);
    ProductionCorrection.Changes ch = c.changes();
    validateCitizenRef(ch.citizenRef(), "changes.citizen_ref");
    if (present(ch.professionalCns()) && !Cns.isValid(ch.professionalCns())) {
      throw DomainValidationException.field(
          "changes.professional_cns", "CNS do profissional inválido");
    }
    Map<String, Object> changes = new LinkedHashMap<>();
    change(changes, "cnes", r.cnes, ch.cnes(), v -> r.cnes = v);
    change(
        changes,
        "professional_cbo",
        r.professionalCbo,
        ch.professionalCbo(),
        v -> r.professionalCbo = v);
    change(
        changes, "procedure_code", r.procedureCode, ch.procedureCode(), v -> r.procedureCode = v);
    change(changes, "competence", r.competence, ch.competence(), v -> r.competence = v);
    change(changes, "cid_code", r.cidCode, upper(ch.cidCode()), v -> r.cidCode = v);
    change(
        changes,
        "character_of_care",
        r.characterOfCare,
        ch.characterOfCare(),
        v -> r.characterOfCare = v);
    change(changes, "apac_number", r.apacNumber, blank(ch.apacNumber()), v -> r.apacNumber = v);
    change(changes, "aih_number", r.aihNumber, blank(ch.aihNumber()), v -> r.aihNumber = v);
    if (ch.quantity() != null && ch.quantity() != r.quantity) {
      changes.put("quantity", Map.of("from", r.quantity, "to", ch.quantity()));
      r.quantity = ch.quantity();
    }
    if (ch.attendanceDate() != null && !ch.attendanceDate().equals(r.attendanceDate)) {
      changes.put(
          "attendance_date",
          Map.of("from", r.attendanceDate.toString(), "to", ch.attendanceDate().toString()));
      r.attendanceDate = ch.attendanceDate();
    }
    if (present(ch.professionalCns())) {
      setProfessionalCns(r, ch.professionalCns());
      changes.put("professional_cns", "changed");
    }
    if (ch.encounterRef() != null
        || ch.appointmentRef() != null
        || ch.hospitalEpisodeRef() != null) {
      setRefs(r, ch.encounterRef(), ch.appointmentRef(), ch.hospitalEpisodeRef());
      changes.put("refs", "changed");
    }
    CitizenDetail citizen;
    if (ch.citizenRef() != null && ch.citizenRef().present()) {
      citizen = resolveCitizen(r, ch.citizenRef());
      changes.put("citizen_ref", "changed");
    } else {
      citizen = r.citizenId == null ? null : safeCitizen(r.citizenId);
    }

    // avisos dispensados com a mesma justificativa (erros não são dispensáveis)
    if (c.waiveIssueIds() != null) {
      for (String issueId : c.waiveIssueIds()) {
        ProductionValidationIssue i = issues.findById(issueId);
        if (i == null || !i.recordId.equals(r.id)) {
          throw DomainValidationException.field(
              "waive_issue_ids", "pendência inexistente: " + issueId);
        }
        if (i.error()) {
          throw DomainValidationException.field(
              "waive_issue_ids", "pendência de erro não pode ser dispensada: " + i.ruleId);
        }
        if (i.open()) {
          resolveIssue(i, "waived", "dispensada: " + justification);
          events.publishIssue("issue_resolved", i, r);
          changes.put("waived:" + i.ruleId, i.id);
        }
      }
    }
    if (changes.isEmpty()) {
      throw DomainValidationException.field("changes", "nenhuma alteração informada");
    }

    // pendências não-regra encerradas pela correção humana (motivo oficial; prazo, se reaberto)
    boolean competenceOpen = !Instant.now().isAfter(deadlines.resolve(r.competence).deadlineAt());
    for (ProductionValidationIssue i : issues.openByRecord(r.id)) {
      if (OFFICIAL_REJECTION.equals(i.ruleId)
          || (DEADLINE_MISSED.equals(i.ruleId) && competenceOpen)) {
        resolveIssue(i, "resolved", "corrigido: " + justification);
        events.publishIssue("issue_resolved", i, r);
      }
    }

    String previous = r.status;
    if ("rejected".equals(previous)) {
      r.batchId = null; // reapresentação em novo lote
    }
    r.status = ProductionRecordStatus.CORRECTED.wire();
    r.correctionCount++;
    r.lastCorrectedAt = Instant.now();
    r.updatedAt = r.lastCorrectedAt;
    addHistory(r, "corrected", previous, r.status, changes, justification);
    events.publishRecord("corrected", r, 0, 0, null);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("fields", new ArrayList<>(changes.keySet()));
    details.put("from_status", previous);
    audit.record(
        AuditEntry.of("production.record.corrected", "production_record", r.id, r.citizenId)
            .withReason(truncate(justification, 500))
            .withDetails(details));
    preAudit(r, citizen, true);
    starter.signalCorrectedAfterCommit(r.id);
    LOG.infof("produção %s corrigida (%s → %s)", r.id, previous, r.status);
    return toDto(r, true);
  }

  private interface Setter {
    void set(String v);
  }

  private static void change(
      Map<String, Object> changes, String f, String from, String to, Setter s) {
    if (to != null && !to.isBlank() && !to.trim().equals(from)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("from", from);
      m.put("to", to.trim());
      changes.put(f, m);
      s.set(to.trim());
    }
  }

  /** Registro em lote rascunho volta a ser elegível (sai do lote) ao ser corrigido/reenviado. */
  private void detachFromDraftBatch(ProductionRecord r) {
    if (r.batchId == null || "rejected".equals(r.status)) {
      return;
    }
    ProductionBatch b = batches.findById(r.batchId);
    if (b == null) {
      r.batchId = null;
      return;
    }
    if (!"draft".equals(b.status)) {
      throw new ConflictException(
          "registro em lote " + b.id + " (" + b.status + "): alteração bloqueada após aprovação");
    }
    batchItems
        .activeFor(b.id, r.id)
        .ifPresent(
            item -> {
              item.removedAt = Instant.now();
              b.recordsCount--;
              b.totalQuantity -= r.quantity;
              if (r.estimatedValue != null) {
                b.estimatedValue = b.estimatedValue.subtract(r.estimatedValue);
              }
              b.updatedAt = Instant.now();
            });
    r.batchId = null;
  }

  // ---------------------------------------------------------------------
  // consultas
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ProductionRecordDto get(String recordId) {
    return toDto(load(recordId), true);
  }

  @Override
  @TenantTransactional
  public Optional<ProductionRecordDto> findBySource(String sourceSystem, String sourceRecordId) {
    return links
        .findBySource(tenantContext.require(), sourceSystem, sourceRecordId)
        .map(l -> records.findById(l.recordId))
        .filter(Objects::nonNull)
        .map(r -> toDto(r, true));
  }

  @Override
  @TenantTransactional
  public Page<ProductionRecordDto> list(
      String competence,
      String cnes,
      ProductionKind kind,
      ProductionRecordStatus status,
      String procedureCode,
      String citizenId,
      String batchId,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    List<ProductionRecordDto> rows =
        records
            .list(
                blank(competence),
                blank(cnes),
                kind == null ? null : kind.wire(),
                status == null ? null : status.wire(),
                blank(procedureCode),
                blank(citizenId),
                blank(batchId),
                Cursor.decode(cursor).orElse(null),
                size + 1)
            .stream()
            .map(r -> toDto(r, false))
            .toList();
    return Page.of(rows, size, ProductionRecordDto::id);
  }

  @Override
  @TenantTransactional
  public Page<ProductionIssueDto> issues(
      String severity,
      String ruleId,
      String competence,
      String cnes,
      ProductionKind kind,
      String status,
      String recordId,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    List<ProductionIssueDto> rows =
        issues
            .list(
                blank(severity),
                blank(ruleId),
                blank(competence),
                blank(cnes),
                kind == null ? null : kind.wire(),
                blank(status) == null ? "open" : status.trim(),
                blank(recordId),
                Cursor.decode(cursor).orElse(null),
                size + 1)
            .stream()
            .map(row -> issueDto((ProductionValidationIssue) row[0], (ProductionRecord) row[1]))
            .toList();
    return Page.of(rows, size, ProductionIssueDto::id);
  }

  // ---------------------------------------------------------------------
  // lotes (PRO-005/010)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ProductionBatchDto createBatch(ProductionBatchCreate c) {
    rejectAgents("gerar lote de produção");
    List<ProductionRecord> eligible =
        records.validatedWithoutBatch(c.competence(), c.cnes(), c.kind().wire());
    if (eligible.isEmpty()) {
      throw new DomainValidationException(
          "nenhum registro validated elegível para a competência/CNES/tipo informados");
    }
    ProductionBatch b = new ProductionBatch();
    b.id = Ulid.generate(Ulid.PRODUCTION_BATCH);
    b.tenantId = tenantContext.require();
    b.competence = c.competence();
    b.cnes = c.cnes();
    b.kind = c.kind().wire();
    b.status = "draft";
    b.createdBy = currentActor.actorId();
    batches.persist(b);
    int line = 0;
    for (ProductionRecord r : eligible) {
      ProductionBatchItem item = new ProductionBatchItem();
      item.id = Ulid.generate(Ulid.PRODUCTION_BATCH_ITEM);
      item.tenantId = b.tenantId;
      item.batchId = b.id;
      item.recordId = r.id;
      item.lineNumber = ++line;
      batchItems.persist(item);
      r.batchId = b.id;
      r.updatedAt = Instant.now();
      b.recordsCount++;
      b.totalQuantity += r.quantity;
      if (r.estimatedValue != null) {
        b.estimatedValue = b.estimatedValue.add(r.estimatedValue);
      }
      addHistory(r, "batched", r.status, r.status, Map.of("batch_id", b.id), null);
    }
    events.publishBatch("batch_generated", b, actorKind(), null, null);
    audit.record(
        AuditEntry.of("production.batch.generated", "production_batch", b.id, null)
            .withDetails(Map.of("records", b.recordsCount, "competence", b.competence)));
    LOG.infof("lote %s gerado: %d registro(s) %s %s", b.id, b.recordsCount, b.kind, b.competence);
    return batchDto(b);
  }

  @Override
  @TenantTransactional
  public ProductionBatchDto getBatch(String batchId) {
    return batchDto(loadBatch(batchId));
  }

  @Override
  @TenantTransactional
  public Page<ProductionBatchDto> listBatches(
      String competence, String cnes, String status, String cursor, Integer limit) {
    int size = Cursor.limit(limit);
    List<ProductionBatchDto> rows =
        batches
            .list(
                blank(competence),
                blank(cnes),
                blank(status),
                Cursor.decode(cursor).orElse(null),
                size + 1)
            .stream()
            .map(this::batchDto)
            .toList();
    return Page.of(rows, size, ProductionBatchDto::id);
  }

  @Override
  @TenantTransactional
  public ProductionBatchDto approveBatch(String batchId, ProductionBatchApproval approval) {
    rejectAgents("aprovar lote de produção");
    if (!currentActor.hasRole(Roles.AUDITOR) && !currentActor.hasRole(Roles.GESTOR)) {
      throw forbidden("aprovação de lote exige papel auditor ou gestor (PRO-010)");
    }
    if (approval == null
        || approval.justification() == null
        || approval.justification().trim().length() < 10) {
      throw DomainValidationException.field("justification", "justificativa obrigatória");
    }
    ProductionBatch b = loadBatch(batchId);
    if (!"draft".equals(b.status)) {
      throw new ConflictException("lote em " + b.status + " não pode ser aprovado");
    }
    if (b.recordsCount <= 0) {
      throw new ConflictException("lote sem registros ativos");
    }
    for (ProductionRecord r : records.byBatch(b.id)) {
      if (!"validated".equals(r.status)) {
        throw new ConflictException("lote contém registro não validado: " + r.id);
      }
    }
    b.status = "approved";
    b.approvedBy = currentActor.actorId();
    b.approvedAt = Instant.now();
    b.approvalJustification = approval.justification().trim();
    b.updatedAt = b.approvedAt;
    events.publishBatch("batch_approved", b, actorKind(), null, null);
    audit.record(
        AuditEntry.of("production.batch.approved", "production_batch", b.id, null)
            .withReason(truncate(b.approvalJustification, 500))
            .withDetails(Map.of("records", b.recordsCount, "competence", b.competence)));
    return batchDto(b);
  }

  @Override
  @TenantTransactional
  public ProductionBatchDto exportBatch(String batchId, ProductionBatchExportRequest request) {
    rejectAgents("exportar lote de produção");
    ProductionBatch b = loadBatch(batchId);
    if (!"approved".equals(b.status)) {
      throw new ConflictException(
          "lote em "
              + b.status
              + ": exportação exige lote aprovado por humano (auditor/gestor) e ainda não exportado");
    }
    String layout =
        request != null && present(request.layout())
            ? request.layout()
            : ("bpa_c".equals(b.kind) || "bpa_i".equals(b.kind)
                ? ExportLayouts.BPA_MAG_REF_V1
                : ExportLayouts.CSV_REF_V1);
    if (ExportLayouts.BPA_MAG_REF_V1.equals(layout)
        && !("bpa_c".equals(b.kind) || "bpa_i".equals(b.kind))) {
      throw DomainValidationException.field("layout", "bpa_mag_ref_v1 só para BPA-C/BPA-I");
    }
    List<ProductionRecord> rows = records.byBatch(b.id);
    List<ExportLayouts.Line> lines = new ArrayList<>();
    for (ProductionRecord r : rows) {
      lines.add(line(r));
    }
    ExportLayouts.Rendered rendered =
        ExportLayouts.BPA_MAG_REF_V1.equals(layout)
            ? ExportLayouts.bpaMag(
                b.competence,
                lines,
                new ExportLayouts.Header(
                    exportOriginName, exportOriginAcronym, exportOriginDocument))
            : ExportLayouts.csv(lines);
    String name =
        b.competence
            + "_"
            + b.cnes
            + "_"
            + b.kind
            + "_"
            + b.id
            + "_"
            + System.currentTimeMillis()
            + "."
            + rendered.extension();
    ExportStorage.StoredFile file = storage.store(b.tenantId, name, rendered.content());
    Instant now = Instant.now();
    b.status = "exported";
    b.exportLayout = layout;
    b.exportFileRef = file.ref();
    b.exportSha256 = file.sha256();
    b.exportSizeBytes = file.sizeBytes();
    b.exportLines = rendered.lines();
    b.exportMissingIds = rendered.missingIdentifiers();
    b.exportedBy = currentActor.actorId();
    b.exportedAt = now;
    b.updatedAt = now;

    ProductionSubmission s = new ProductionSubmission();
    s.id = Ulid.generate(Ulid.PRODUCTION_SUBMISSION);
    s.tenantId = b.tenantId;
    s.batchId = b.id;
    s.kind = "export";
    s.layout = layout;
    s.fileRef = file.ref();
    s.sha256 = file.sha256();
    s.sizeBytes = file.sizeBytes();
    s.lines = rendered.lines();
    s.actorId = currentActor.actorId();
    entityManager.persist(s);

    for (ProductionRecord r : rows) {
      String previous = r.status;
      r.status = ProductionRecordStatus.EXPORTED.wire();
      r.exportedAt = now;
      r.updatedAt = now;
      addHistory(r, "exported", previous, r.status, Map.of("batch_id", b.id), null);
    }
    events.publishBatch("exported", b, actorKind(), file.ref(), null);
    audit.record(
        AuditEntry.of("production.batch.exported", "production_batch", b.id, null)
            .withDetails(
                Map.of("layout", layout, "sha256", file.sha256(), "lines", rendered.lines())));
    LOG.infof("lote %s exportado (%s, %d linha(s))", b.id, layout, rendered.lines());
    return batchDto(b);
  }

  /** Linha de exportação: CNS decifrados só para o arquivo (nunca logados/retornados). */
  private ExportLayouts.Line line(ProductionRecord r) {
    String professionalCns =
        r.professionalCnsEnc == null ? null : cipher.decrypt(r.professionalCnsEnc);
    String citizenCns =
        r.citizenIdentifierEnc != null && "CNS".equals(r.citizenIdentifierSystem)
            ? cipher.decrypt(r.citizenIdentifierEnc)
            : null;
    String sex = null;
    Integer age = null;
    String ibge = null;
    CitizenDetail c = r.citizenId == null ? null : safeCitizen(r.citizenId);
    if (c != null) {
      sex = c.sex() == Sex.FEMALE ? "F" : c.sex() == Sex.MALE ? "M" : null;
      age =
          c.birthdate() == null ? null : Period.between(c.birthdate(), r.attendanceDate).getYears();
      ibge = c.address() == null ? null : c.address().cityIbge();
    }
    String authorization =
        "apac".equals(r.kind) ? r.apacNumber : "aih".equals(r.kind) ? r.aihNumber : null;
    return new ExportLayouts.Line(
        r.id,
        r.kind,
        r.cnes,
        r.competence,
        professionalCns,
        r.professionalCbo,
        r.attendanceDate,
        r.procedureCode,
        citizenCns,
        sex,
        ibge,
        r.cidCode,
        age,
        r.quantity,
        r.characterOfCare,
        authorization);
  }

  // ---------------------------------------------------------------------
  // retornos oficiais (PRO-008)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ProductionOutcomeResult registerOutcome(ProductionOutcomeRegistration o) {
    rejectAgents("registrar retorno oficial de produção");
    int targets =
        (present(o.productionRecordId()) ? 1 : 0)
            + (o.recordSource() != null && o.recordSource().present() ? 1 : 0)
            + (present(o.batchId()) ? 1 : 0);
    if (targets != 1) {
      throw new DomainValidationException(
          "informe exatamente um entre production_record_id, record_source e batch_id");
    }
    String outcome = o.outcome();
    if (present(o.batchId())) {
      if ("rejected".equals(outcome) || "paid".equals(outcome)) {
        throw DomainValidationException.field(
            "batch_id", "rejected/paid exigem retorno por registro (production_record_id)");
      }
      return batchOutcome(o);
    }
    if ("paid".equals(outcome) && o.paidAmount() == null) {
      throw DomainValidationException.field("paid_amount", "obrigatório para paid");
    }
    ProductionRecord r = recordFor(o);
    Optional<ProductionOutcome> existing =
        outcomes.existing(o.source().system(), o.source().sourceRecordId(), outcome, r.id, null);
    if (existing.isPresent()) {
      return new ProductionOutcomeResult(
          outcome,
          r.batchId,
          true,
          List.of(
              new ProductionOutcomeResult.Affected(
                  r.id, ProductionRecordStatus.fromWire(r.status))));
    }
    applyRecordOutcome(r, o);
    refreshBatch(r.batchId);
    return new ProductionOutcomeResult(
        outcome,
        r.batchId,
        false,
        List.of(
            new ProductionOutcomeResult.Affected(r.id, ProductionRecordStatus.fromWire(r.status))));
  }

  private ProductionRecord recordFor(ProductionOutcomeRegistration o) {
    if (present(o.productionRecordId())) {
      return load(o.productionRecordId().trim());
    }
    ExternalRef ref = o.recordSource();
    String system = present(ref.system()) ? ref.system() : o.source().system();
    return links
        .findBySource(tenantContext.require(), system, ref.sourceRecordId())
        .map(l -> load(l.recordId))
        .orElseThrow(
            () ->
                new NotFoundException("registro de produção", system + "/" + ref.sourceRecordId()));
  }

  private ProductionOutcomeResult batchOutcome(ProductionOutcomeRegistration o) {
    ProductionBatch b = loadBatch(o.batchId().trim());
    if (!Set.of("exported", "transmitted", "processed").contains(b.status)) {
      throw new ConflictException("lote em " + b.status + ": ainda não exportado");
    }
    if (outcomes
        .existing(o.source().system(), o.source().sourceRecordId(), o.outcome(), null, b.id)
        .isPresent()) {
      return new ProductionOutcomeResult(o.outcome(), b.id, true, List.of());
    }
    ProductionOutcome row = newOutcome(o, null, b.id);
    entityManager.persist(row);
    List<ProductionOutcomeResult.Affected> affected = new ArrayList<>();
    for (ProductionRecord r : records.byBatch(b.id)) {
      if (applyRecordOutcomeIfEligible(r, o)) {
        affected.add(
            new ProductionOutcomeResult.Affected(r.id, ProductionRecordStatus.fromWire(r.status)));
      }
    }
    if ("transmitted".equals(o.outcome())) {
      if ("exported".equals(b.status)) {
        b.status = "transmitted";
      }
      b.protocolNumber = blank(o.protocolNumber());
      b.updatedAt = Instant.now();
      ProductionSubmission s = new ProductionSubmission();
      s.id = Ulid.generate(Ulid.PRODUCTION_SUBMISSION);
      s.tenantId = b.tenantId;
      s.batchId = b.id;
      s.kind = "transmission";
      s.protocolNumber = blank(o.protocolNumber());
      s.actorId = currentActor.actorId();
      s.occurredAt = micros(o.processedAt());
      entityManager.persist(s);
      events.publishBatch("transmitted", b, "official_system", null, null);
    }
    refreshBatch(b.id);
    return new ProductionOutcomeResult(o.outcome(), b.id, false, affected);
  }

  /** Aplica o retorno de lote ao registro quando a transição é válida (ignora os demais). */
  private boolean applyRecordOutcomeIfEligible(
      ProductionRecord r, ProductionOutcomeRegistration o) {
    try {
      return applyRecordOutcome(r, o);
    } catch (ConflictException e) {
      return false;
    }
  }

  /** Transição do registro pelo retorno oficial (linha de retorno por registro, idempotente). */
  private boolean applyRecordOutcome(ProductionRecord r, ProductionOutcomeRegistration o) {
    String outcome = o.outcome();
    String from = r.status;
    String to =
        switch (outcome) {
          case "transmitted" -> allowed(from, Set.of("exported"), "transmitted");
          case "received" -> allowed(from, Set.of("exported", "transmitted"), "received");
          case "accepted" ->
              allowed(from, Set.of("exported", "transmitted", "received"), "approved");
          case "rejected" ->
              allowed(from, Set.of("exported", "transmitted", "received", "approved"), "rejected");
          case "paid" ->
              allowed(from, Set.of("exported", "transmitted", "received", "approved"), "paid");
          default -> throw DomainValidationException.field("outcome", "desconhecido");
        };
    Instant now = Instant.now();
    ProductionOutcome row = newOutcome(o, r.id, r.batchId);
    entityManager.persist(row);
    r.status = to;
    r.updatedAt = now;
    if (o.approvedQuantity() != null) {
      r.approvedQuantity = o.approvedQuantity();
    }
    if ("paid".equals(outcome)) {
      r.paidAmount = o.paidAmount().setScale(2, RoundingMode.HALF_UP);
    }
    if ("rejected".equals(outcome)) {
      r.outcomeReasonCode = blank(o.reasonCode());
      r.outcomeReason = truncate(blank(o.reason()), 500);
    }
    Map<String, Object> changes = new LinkedHashMap<>();
    changes.put("source", o.source().system() + "/" + o.source().sourceRecordId());
    if (o.reasonCode() != null) {
      changes.put("reason_code", o.reasonCode());
    }
    if (o.paidAmount() != null) {
      changes.put("paid_amount", o.paidAmount());
    }
    addHistory(r, "outcome:" + outcome, from, to, changes, null);
    if ("rejected".equals(outcome)) {
      reopenForRejection(r, o);
    }
    if (!"transmitted".equals(outcome)) {
      events.publishOutcome(row, r, o.source().system());
    }
    return true;
  }

  private static String allowed(String from, Set<String> fromSet, String to) {
    if (to.equals(from)) {
      throw new ConflictException("registro já em " + to);
    }
    if (!fromSet.contains(from)) {
      throw new ConflictException("retorno incompatível com o status do registro (" + from + ")");
    }
    return to;
  }

  /** Rejeição oficial reabre pendência com o motivo oficial + tarefa na fila auditoria. */
  private void reopenForRejection(ProductionRecord r, ProductionOutcomeRegistration o) {
    ProductionValidationIssue i =
        issues.openByRecord(r.id).stream()
            .filter(x -> OFFICIAL_REJECTION.equals(x.ruleId))
            .findFirst()
            .orElse(null);
    String message =
        truncate(
            "Rejeitado no processamento oficial"
                + (present(o.reasonCode()) ? " [" + o.reasonCode() + "]" : "")
                + (present(o.reason()) ? ": " + o.reason() : ""),
            1000);
    if (i == null) {
      i = new ProductionValidationIssue();
      i.id = Ulid.generate(Ulid.PRODUCTION_ISSUE);
      i.tenantId = r.tenantId;
      i.recordId = r.id;
      i.ruleId = OFFICIAL_REJECTION;
      i.ruleVersion = "official-return/" + o.source().system();
      i.severity = "error";
      i.field = null;
      i.message = message;
      i.status = "open";
      i.origin = "official_return";
      i.createdAt = Instant.now();
      issues.persist(i);
    } else {
      i.message = message;
    }
    i.taskId = ensureTask(r, issues.openByRecord(r.id), 1);
    events.publishIssue("issue_found", i, r);
  }

  private ProductionOutcome newOutcome(
      ProductionOutcomeRegistration o, String recordId, String batchId) {
    ProductionOutcome row = new ProductionOutcome();
    row.id = Ulid.generate(Ulid.PRODUCTION_OUTCOME);
    row.tenantId = tenantContext.require();
    row.recordId = recordId;
    row.batchId = batchId;
    row.outcome = o.outcome();
    row.reasonCode = blank(o.reasonCode());
    row.reason = truncate(blank(o.reason()), 500);
    row.paidAmount =
        o.paidAmount() == null ? null : o.paidAmount().setScale(2, RoundingMode.HALF_UP);
    row.approvedQuantity = o.approvedQuantity();
    row.protocolNumber = blank(o.protocolNumber());
    row.processedAt = micros(o.processedAt());
    row.sourceSystem = o.source().system();
    row.sourceRecordId = o.source().sourceRecordId();
    return row;
  }

  /** Lote processado quando todos os registros têm desfecho final (aprovado, pago, rejeitado). */
  private void refreshBatch(String batchId) {
    if (batchId == null) {
      return;
    }
    ProductionBatch b = batches.findById(batchId);
    if (b == null || !Set.of("exported", "transmitted").contains(b.status)) {
      return;
    }
    List<ProductionRecord> rows = records.byBatch(b.id);
    boolean done =
        !rows.isEmpty()
            && rows.stream()
                .allMatch(r -> Set.of("approved", "paid", "rejected").contains(r.status));
    if (done) {
      b.status = "processed";
      b.updatedAt = Instant.now();
    }
  }

  // ---------------------------------------------------------------------
  // painel (PRO-009) e prazos (PRO-007)
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public ProductionSummary summary(String competence, String cnes) {
    if (!Competence.isValid(competence)) {
      throw DomainValidationException.field("competence", "competência inválida (AAAAMM)");
    }
    String filter = " where competence = ?1" + (present(cnes) ? " and cnes = ?2" : "");
    var q =
        entityManager.createNativeQuery(
            "select count(*),"
                + " count(*) filter (where status = 'generated'),"
                + " count(*) filter (where status = 'validated'),"
                + " count(*) filter (where status = 'pending'),"
                + " count(*) filter (where status = 'exported'),"
                + " count(*) filter (where status = 'transmitted'),"
                + " count(*) filter (where status = 'received'),"
                + " count(*) filter (where status = 'rejected'),"
                + " count(*) filter (where correction_count > 0),"
                + " count(*) filter (where status = 'approved'),"
                + " count(*) filter (where status = 'paid'),"
                + " coalesce(sum(estimated_value), 0),"
                + " coalesce(sum(estimated_value) filter (where status = 'validated'), 0),"
                + " coalesce(sum(paid_amount), 0),"
                + " coalesce(sum(estimated_value) filter (where status in ('pending','generated','corrected')), 0),"
                + " coalesce(sum(estimated_value) filter (where status = 'rejected'), 0)"
                + " from production.production_record"
                + filter);
    q.setParameter(1, competence);
    if (present(cnes)) {
      q.setParameter(2, cnes.trim());
    }
    Object[] row = (Object[]) q.getSingleResult();
    ProductionSummary.Totals totals =
        new ProductionSummary.Totals(
            n(row[0]),
            n(row[1]),
            n(row[2]),
            n(row[3]),
            n(row[4]),
            n(row[5]),
            n(row[6]),
            n(row[7]),
            n(row[8]),
            n(row[9]),
            n(row[10]));
    BigDecimal pendingValue = money(row[14]);
    BigDecimal rejectedValue = money(row[15]);
    ProductionSummary.Values values =
        new ProductionSummary.Values(
            money(row[11]),
            money(row[12]),
            money(row[13]),
            pendingValue,
            rejectedValue,
            pendingValue.add(rejectedValue));
    List<ProductionSummary.RuleCount> byRule =
        issues.openByRule(competence, blank(cnes)).stream()
            .map(r -> new ProductionSummary.RuleCount((String) r[0], (String) r[1], n(r[2])))
            .toList();
    var kq =
        entityManager.createNativeQuery(
            "select kind, count(*), coalesce(sum(estimated_value), 0) from production.production_record"
                + filter
                + " group by kind order by kind");
    kq.setParameter(1, competence);
    if (present(cnes)) {
      kq.setParameter(2, cnes.trim());
    }
    @SuppressWarnings("unchecked")
    List<Object[]> kinds = kq.getResultList();
    List<ProductionSummary.KindCount> byKind =
        kinds.stream()
            .map(
                k ->
                    new ProductionSummary.KindCount(
                        ProductionKind.fromWire((String) k[0]), n(k[1]), money(k[2])))
            .toList();
    ProductionDeadlines.Deadline d = deadlines.resolve(competence);
    return new ProductionSummary(
        competence,
        blank(cnes),
        d.deadlineAt().atOffset(ZoneOffset.UTC),
        (int) ChronoUnit.DAYS.between(Instant.now(), d.deadlineAt()),
        totals,
        values,
        byRule,
        byKind);
  }

  @Override
  @TenantTransactional
  public List<ProductionDeadlineDto> deadlines(String fromCompetence, String toCompetence) {
    YearMonth current = YearMonth.now(PreAuditor.ZONE);
    YearMonth from =
        Competence.isValid(fromCompetence)
            ? new Competence(fromCompetence).toYearMonth()
            : current.minusMonths(2);
    YearMonth to =
        Competence.isValid(toCompetence)
            ? new Competence(toCompetence).toYearMonth()
            : current.plusMonths(1);
    if (to.isBefore(from) || from.plusMonths(36).isBefore(to)) {
      throw DomainValidationException.field("to", "intervalo inválido (máx. 36 competências)");
    }
    Instant now = Instant.now();
    List<ProductionDeadlineDto> out = new ArrayList<>();
    for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) {
      String comp = Competence.of(ym).value();
      ProductionDeadlines.Deadline d = deadlines.resolve(comp);
      long days = ChronoUnit.DAYS.between(now, d.deadlineAt());
      String status =
          now.isAfter(d.deadlineAt())
              ? "closed"
              : Duration.between(now, d.deadlineAt()).compareTo(Duration.ofDays(5)) <= 0
                  ? "closing"
                  : "open";
      out.add(
          new ProductionDeadlineDto(
              comp,
              d.deadlineAt().atOffset(ZoneOffset.UTC),
              status,
              days,
              d.alertDays(),
              d.by(),
              records.countByCompetenceAndStatus(comp, "pending"),
              records.countByCompetenceAndStatus(comp, "validated")));
    }
    return out;
  }

  // ---------------------------------------------------------------------
  // suporte ao workflow e ao job de prazos
  // ---------------------------------------------------------------------

  @Override
  @TenantTransactional
  public Optional<PreAuditSnapshot> preAuditSnapshot(String recordId) {
    ProductionRecord r = records.findById(recordId);
    if (r == null) {
      return Optional.empty();
    }
    int errors =
        (int) issues.openByRecord(r.id).stream().filter(ProductionValidationIssue::error).count();
    return Optional.of(
        new PreAuditSnapshot(
            r.status, r.deadlineAt == null ? null : r.deadlineAt.toString(), errors));
  }

  @Override
  @TenantTransactional
  public String revalidate(String recordId) {
    ProductionRecord r = records.findById(recordId);
    if (r == null) {
      return "unknown";
    }
    if (!ProductionRecordStatus.fromWire(r.status).inPreAudit() || r.batchId != null) {
      return r.status;
    }
    preAudit(r, r.citizenId == null ? null : safeCitizen(r.citizenId), false);
    return r.status;
  }

  @Override
  @TenantTransactional
  public String expire(String recordId) {
    ProductionRecord r = records.findById(recordId);
    if (r == null) {
      return "unknown";
    }
    if (!"pending".equals(r.status)) {
      return r.status;
    }
    boolean exists =
        issues.openByRecord(r.id).stream().anyMatch(i -> DEADLINE_MISSED.equals(i.ruleId));
    if (!exists) {
      ProductionValidationIssue i = new ProductionValidationIssue();
      i.id = Ulid.generate(Ulid.PRODUCTION_ISSUE);
      i.tenantId = r.tenantId;
      i.recordId = r.id;
      i.ruleId = DEADLINE_MISSED;
      i.ruleVersion = r.ruleVersion == null ? PreAuditor.RULE_SET : r.ruleVersion;
      i.severity = "error";
      i.field = "competence";
      i.message =
          "Prazo de apresentação da competência "
              + r.competence
              + " vencido com pendências não corrigidas";
      i.status = "open";
      i.origin = "workflow";
      i.createdAt = Instant.now();
      issues.persist(i);
      i.taskId = ensureTask(r, issues.openByRecord(r.id), 1);
      addHistory(r, DEADLINE_MISSED, r.status, r.status, Map.of(), null);
      events.publishIssue("issue_found", i, r);
      LOG.infof("produção %s: prazo da competência %s vencido", r.id, r.competence);
    }
    return r.status;
  }

  @Override
  @TenantTransactional
  public Instant deadlineFor(String competence) {
    return deadlines.resolve(competence).deadlineAt();
  }

  @Override
  @TenantTransactional
  public int raiseDeadlineAlerts(Instant now) {
    String tenant = tenantContext.require();
    int created = 0;
    for (String competence : records.openCompetences()) {
      ProductionDeadlines.Deadline d = deadlines.resolve(competence);
      if (!now.isBefore(d.deadlineAt())) {
        continue;
      }
      List<Integer> applicable =
          d.alertDays().stream()
              .filter(days -> !now.isBefore(d.deadlineAt().minus(Duration.ofDays(days))))
              .sorted()
              .toList();
      if (applicable.isEmpty()) {
        continue;
      }
      int most = applicable.get(0);
      String alert = "D-" + most;
      if (alertSent(competence, alert)) {
        continue;
      }
      long open = records.countOpenInCompetence(competence);
      if (open == 0) {
        continue;
      }
      long pending = records.countByCompetenceAndStatus(competence, "pending");
      long hours = Math.max(0, Duration.between(now, d.deadlineAt()).toHours());
      TaskDto task =
          taskCommands.create(
              new TaskCreate(
                  TaskType.PRODUCTION_ISSUE,
                  most <= 1 ? TaskPriority.URGENT : TaskPriority.HIGH,
                  "Prazo da competência "
                      + competence
                      + " vence em "
                      + most
                      + " dia(s) ("
                      + alert
                      + ")",
                  "Faltam "
                      + hours
                      + " h para o prazo de apresentação da competência "
                      + competence
                      + " ("
                      + d.deadlineAt().atOffset(ZoneOffset.UTC)
                      + "). Registros em pré-auditoria: "
                      + open
                      + " (pendentes: "
                      + pending
                      + "). Corrigir pendências, gerar e aprovar os lotes.",
                  null,
                  Assignee.queue(AUDIT_QUEUE),
                  d.deadlineAt().atOffset(ZoneOffset.UTC),
                  null,
                  TaskOrigin.rule(DEADLINE_ORIGIN_PREFIX + competence + ":" + alert, d.by())),
              null);
      for (int days : applicable) {
        markAlert(tenant, competence, "D-" + days, days == most ? task.id() : null);
      }
      created++;
      LOG.infof("alerta %s da competência %s (tarefa %s)", alert, competence, task.id());
    }
    return created;
  }

  private boolean alertSent(String competence, String alert) {
    return ((Number)
                entityManager
                    .createNativeQuery(
                        "select count(*) from production.production_deadline_alert"
                            + " where competence = ?1 and alert = ?2")
                    .setParameter(1, competence)
                    .setParameter(2, alert)
                    .getSingleResult())
            .longValue()
        > 0;
  }

  private void markAlert(String tenant, String competence, String alert, String taskId) {
    entityManager
        .createNativeQuery(
            "insert into production.production_deadline_alert (tenant_id, competence, alert, task_id)"
                + " values (?1, ?2, ?3, ?4) on conflict do nothing")
        .setParameter(1, tenant)
        .setParameter(2, competence)
        .setParameter(3, alert)
        .setParameter(4, taskId)
        .executeUpdate();
  }

  // ---------------------------------------------------------------------
  // DTOs e utilitários
  // ---------------------------------------------------------------------

  ProductionRecordDto toDto(ProductionRecord r, boolean withHistory) {
    List<ProductionIssueDto> issueDtos =
        issues.byRecord(r.id).stream().map(i -> issueDto(i, r)).toList();
    List<ProductionRecordDto.HistoryEntry> hist =
        withHistory
            ? history.byRecord(r.id).stream()
                .map(
                    h ->
                        new ProductionRecordDto.HistoryEntry(
                            h.action,
                            h.fromStatus,
                            h.toStatus,
                            h.actorId,
                            h.justification,
                            h.ruleVersion,
                            offset(h.occurredAt)))
                .toList()
            : null;
    ExternalRef encounter =
        r.encounterSourceRecordId == null
            ? null
            : new ExternalRef(r.encounterSourceSystem, r.encounterSourceRecordId);
    return new ProductionRecordDto(
        r.id,
        ProductionKind.fromWire(r.kind),
        r.competence,
        r.cnes,
        healthUnits.findByCnes(r.cnes).map(HealthUnitDto::name).orElse(null),
        r.professionalCnsMasked,
        r.professionalCbo,
        r.procedureCode,
        terminology
            .find("SIGTAP", r.procedureCode, r.competence)
            .map(CodeDto::display)
            .orElse(null),
        r.quantity,
        r.citizenId,
        r.citizenIdentifierMasked,
        r.cidCode,
        r.attendanceDate,
        r.characterOfCare,
        r.apacNumber,
        r.aihNumber,
        r.appointmentId,
        r.hospitalEpisodeId,
        encounter,
        ProductionRecordStatus.fromWire(r.status),
        r.ruleVersion,
        offset(r.validatedAt),
        r.unitValue,
        r.estimatedValue,
        r.paidAmount,
        r.approvedQuantity,
        r.outcomeReasonCode,
        r.outcomeReason,
        r.batchId,
        offset(r.deadlineAt),
        r.correctionCount,
        issueDtos,
        hist,
        r.sourceSystem,
        r.sourceRecordId,
        r.version);
  }

  static ProductionIssueDto issueDto(ProductionValidationIssue i, ProductionRecord r) {
    return new ProductionIssueDto(
        i.id,
        i.recordId,
        i.ruleId,
        i.ruleVersion,
        i.severity,
        i.field,
        i.message,
        i.status,
        i.origin,
        i.taskId,
        offset(i.createdAt),
        offset(i.resolvedAt),
        i.resolutionNote,
        r.competence,
        r.cnes,
        ProductionKind.fromWire(r.kind),
        r.procedureCode,
        ProductionRecordStatus.fromWire(r.status));
  }

  ProductionBatchDto batchDto(ProductionBatch b) {
    List<String> ids = batchItems.active(b.id).stream().map(i -> i.recordId).toList();
    ProductionBatchDto.Export export =
        b.exportedAt == null
            ? null
            : new ProductionBatchDto.Export(
                b.exportLayout,
                b.exportFileRef,
                b.exportSha256,
                b.exportSizeBytes,
                b.exportLines,
                b.exportMissingIds,
                b.exportedBy,
                offset(b.exportedAt));
    return new ProductionBatchDto(
        b.id,
        b.competence,
        b.cnes,
        ProductionKind.fromWire(b.kind),
        b.status,
        b.recordsCount,
        b.totalQuantity,
        b.estimatedValue,
        ids,
        b.createdBy,
        offset(b.createdAt),
        b.approvedBy,
        offset(b.approvedAt),
        b.approvalJustification,
        export,
        b.protocolNumber,
        b.version);
  }

  private void addHistory(
      ProductionRecord r,
      String action,
      String from,
      String to,
      Map<String, Object> changes,
      String justification) {
    ProductionRecordHistory h = new ProductionRecordHistory();
    h.id = Ulid.generate(Ulid.PRODUCTION_HISTORY);
    h.tenantId = r.tenantId;
    h.recordId = r.id;
    h.action = action;
    h.fromStatus = from;
    h.toStatus = to;
    h.changes = new LinkedHashMap<>(changes);
    h.justification = justification;
    h.ruleVersion = r.ruleVersion;
    h.actorId = currentActor.actorId();
    h.actorKind = actorKind();
    h.occurredAt = Instant.now();
    history.persist(h);
  }

  /** Campos alterados no reenvio da origem (identificadores: só "changed"). */
  private Map<String, Object> diff(ProductionRecord r, ProductionRecordRegistration reg) {
    Map<String, Object> d = new LinkedHashMap<>();
    diff(d, "kind", r.kind, reg.kind().wire());
    diff(d, "competence", r.competence, reg.competence());
    diff(d, "cnes", r.cnes, reg.cnes());
    diff(d, "professional_cbo", r.professionalCbo, reg.professionalCbo());
    diff(d, "procedure_code", r.procedureCode, reg.procedureCode());
    diff(d, "quantity", String.valueOf(r.quantity), String.valueOf(reg.quantity()));
    diff(
        d,
        "attendance_date",
        String.valueOf(r.attendanceDate),
        String.valueOf(reg.attendanceDate()));
    diff(d, "cid_code", r.cidCode, upper(reg.cidCode()));
    return d;
  }

  private static void diff(Map<String, Object> d, String f, String from, String to) {
    if (!Objects.equals(blank(from), blank(to))) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("from", from);
      m.put("to", to);
      d.put(f, m);
    }
  }

  private String payloadHash(ProductionRecordRegistration reg) {
    ObjectNode node = objectMapper.valueToTree(reg);
    node.remove("source");
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(node.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void rejectAgents(String action) {
    if (currentActor.clientType() == CurrentActor.ClientType.AGENT
        || currentActor.hasRole(Roles.AGENTE_IA)) {
      throw forbidden("agentes de IA não podem " + action + " (PRO-010)");
    }
  }

  private static ProblemException forbidden(String detail) {
    return new ProblemException(403, "Acesso negado", detail, "urn:sus-nexus:problem:forbidden");
  }

  private String actorKind() {
    return currentActor.clientType() == CurrentActor.ClientType.SERVICE ? "service" : "user";
  }

  private ProductionRecord load(String id) {
    ProductionRecord r = records.findById(id);
    if (r == null) {
      throw new NotFoundException("registro de produção", id);
    }
    return r;
  }

  private ProductionBatch loadBatch(String id) {
    ProductionBatch b = batches.findById(id);
    if (b == null) {
      throw new NotFoundException("lote de produção", id);
    }
    return b;
  }

  private static long n(Object o) {
    return o == null ? 0 : ((Number) o).longValue();
  }

  private static BigDecimal money(Object o) {
    if (o == null) {
      return BigDecimal.ZERO.setScale(2);
    }
    return new BigDecimal(o.toString()).setScale(2, RoundingMode.HALF_UP);
  }

  private static Instant micros(OffsetDateTime t) {
    return t == null ? null : t.toInstant().truncatedTo(ChronoUnit.MICROS);
  }

  private static OffsetDateTime offset(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private static String digits(String raw) {
    return raw == null ? null : raw.replaceAll("[^0-9]", "");
  }

  private static boolean present(String s) {
    return s != null && !s.isBlank();
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static String upper(String s) {
    return blank(s) == null ? null : s.trim().toUpperCase();
  }

  private static String truncate(String s, int max) {
    return s == null ? null : s.length() > max ? s.substring(0, max) : s;
  }
}
