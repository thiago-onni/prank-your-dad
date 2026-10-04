package br.gov.sus.nexus.core.identity.application;

import br.gov.sus.nexus.core.audit.api.AuditEntry;
import br.gov.sus.nexus.core.audit.api.AuditService;
import br.gov.sus.nexus.core.identity.api.CitizenRegistration;
import br.gov.sus.nexus.core.identity.api.IdentityConfidence;
import br.gov.sus.nexus.core.identity.api.IdentityResolution;
import br.gov.sus.nexus.core.identity.api.MatchClassification;
import br.gov.sus.nexus.core.identity.api.MatchMethod;
import br.gov.sus.nexus.core.identity.api.MergeCaseStatus;
import br.gov.sus.nexus.core.identity.api.RegistrationState;
import br.gov.sus.nexus.core.identity.api.Sex;
import br.gov.sus.nexus.core.identity.domain.Citizen;
import br.gov.sus.nexus.core.identity.domain.CitizenAddress;
import br.gov.sus.nexus.core.identity.domain.CitizenContact;
import br.gov.sus.nexus.core.identity.domain.CitizenDemographicHistory;
import br.gov.sus.nexus.core.identity.domain.CitizenGoldenRecordAttribute;
import br.gov.sus.nexus.core.identity.domain.CitizenIdentifier;
import br.gov.sus.nexus.core.identity.domain.CitizenMatchCandidate;
import br.gov.sus.nexus.core.identity.domain.CitizenMatchEvidence;
import br.gov.sus.nexus.core.identity.domain.CitizenMergeCase;
import br.gov.sus.nexus.core.identity.domain.CitizenSourceLink;
import br.gov.sus.nexus.core.identity.domain.ConflictDetector;
import br.gov.sus.nexus.core.identity.domain.ContactNormalizer;
import br.gov.sus.nexus.core.identity.domain.MatchEvidence;
import br.gov.sus.nexus.core.identity.domain.MatchScore;
import br.gov.sus.nexus.core.identity.domain.NameNormalizer;
import br.gov.sus.nexus.core.identity.domain.PersonFeatures;
import br.gov.sus.nexus.core.identity.domain.ProbabilisticMatcher;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenAddressRepository;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenContactRepository;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenIdentifierRepository;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenRepository;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenSourceLinkRepository;
import br.gov.sus.nexus.core.identity.infrastructure.DemographicHistoryRepository;
import br.gov.sus.nexus.core.identity.infrastructure.GoldenRecordRepository;
import br.gov.sus.nexus.core.identity.infrastructure.MatchCandidateRepository;
import br.gov.sus.nexus.core.identity.infrastructure.MatchEvidenceRepository;
import br.gov.sus.nexus.core.identity.infrastructure.MergeCaseRepository;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.sharedkernel.Masks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Pipeline de resolução de identidade (plano §5.4):
 *
 * <ol>
 *   <li>Normalizar e validar (CNS/CPF, data plausível);
 *   <li>Determinístico: CNS → CPF → source_link → (nome + data nasc. + mãe). Conflito (mesmo
 *       CNS/CPF com data de nascimento diferente, CNS/CPF divergentes ou identificador de outro
 *       cidadão) NUNCA vincula: cria registro divergente e abre caso de fusão;
 *   <li>Probabilístico (Fellegi-Sunter): provável/pendente → SOMENTE fila de revisão (nunca vínculo
 *       automático); abaixo do limiar baixo → novo cidadão;
 *   <li>Persistir vínculo, evidências e proveniência; publicar evento via outbox.
 * </ol>
 */
@ApplicationScoped
public class IdentityResolutionService {

  private static final Logger LOG = Logger.getLogger(IdentityResolutionService.class);
  private static final int MAX_CASE_CANDIDATES = 4;

  @Inject CitizenRepository citizens;
  @Inject CitizenIdentifierRepository identifiers;
  @Inject CitizenSourceLinkRepository sourceLinks;
  @Inject CitizenAddressRepository addresses;
  @Inject CitizenContactRepository contacts;
  @Inject DemographicHistoryRepository history;
  @Inject GoldenRecordRepository goldenRecords;
  @Inject MergeCaseRepository mergeCases;
  @Inject MatchCandidateRepository matchCandidates;
  @Inject MatchEvidenceRepository matchEvidences;
  @Inject IdentifierCodec codec;
  @Inject FeatureExtractor features;
  @Inject ProbabilisticMatcher matcher;
  @Inject MpiConfig config;
  @Inject IdentityEvents events;
  @Inject AuditService audit;
  @Inject TenantContext tenantContext;

  @TenantTransactional
  public IdentityResolution register(CitizenRegistration reg) {
    validate(reg);
    String tenant = tenantContext.require();
    List<IdentifierCodec.Parsed> parsed = codec.parse(tenant, reg.identifiers());
    List<IdentifierCodec.Parsed> valid =
        parsed.stream().filter(IdentifierCodec.Parsed::valid).toList();
    PersonFeatures incoming = features.fromRegistration(reg, valid);

    // donos ativos dos identificadores de entrada (seguindo fusões)
    Map<String, String> owners = new HashMap<>();
    for (IdentifierCodec.Parsed p : valid) {
      identifiers
          .findActive(tenant, p.systemName(), p.hash())
          .ifPresent(ci -> owners.putIfAbsent(p.systemName(), resolveOwnerId(ci.citizenId)));
    }

    // 3. determinístico
    Citizen candidate = null;
    MatchMethod method = null;
    if (owners.containsKey("CNS")) {
      candidate = citizens.findById(owners.get("CNS"));
      method = MatchMethod.DETERMINISTIC_CNS;
    } else if (owners.containsKey("CPF")) {
      candidate = citizens.findById(owners.get("CPF"));
      method = MatchMethod.DETERMINISTIC_CPF;
    } else {
      Optional<CitizenSourceLink> link =
          sourceLinks.findBySource(tenant, reg.source().system(), reg.source().sourceRecordId());
      if (link.isPresent()) {
        candidate = citizens.resolveMerged(citizens.findById(link.get().citizenId));
        method = MatchMethod.DETERMINISTIC_SOURCE_LINK;
      } else if (incoming.normalizedMotherName() != null) {
        candidate =
            citizens
                .findByDemographics(
                    tenant,
                    incoming.normalizedName(),
                    incoming.birthdate(),
                    incoming.normalizedMotherName())
                .orElse(null);
        method = candidate == null ? null : MatchMethod.DETERMINISTIC_DEMOGRAPHICS;
      }
    }

    if (candidate != null) {
      PersonFeatures existing = features.fromCitizen(candidate);
      ConflictDetector.Result conflicts = ConflictDetector.detect(incoming, existing, owners);
      if (!conflicts.hasConflicts()) {
        return link(reg, parsed, owners, candidate, method, conflicts.evidences());
      }
      LOG.infof(
          "conflito de identidade (%s): %s — abrindo caso de revisão",
          method.wire(), conflicts.conflicts());
      return openConflictCase(reg, parsed, owners, candidate, method, conflicts);
    }

    // 4. probabilístico
    List<Citizen> block =
        citizens.blockingCandidates(
            tenant,
            incoming.normalizedName(),
            incoming.birthdate(),
            config.blocking().minSimilarity(),
            config.blocking().limit());
    List<MatchScore> scored =
        block.stream()
            .map(c -> matcher.compare(incoming, features.fromCitizen(c)))
            .sorted(Comparator.comparingDouble(MatchScore::score).reversed())
            .toList();
    MatchScore best = scored.isEmpty() ? null : scored.get(0);
    ProbabilisticMatcher.Classification cls =
        best == null ? ProbabilisticMatcher.Classification.NEW : matcher.classify(best.score());

    if (cls == ProbabilisticMatcher.Classification.NEW) {
      return createNew(
          reg,
          parsed,
          owners,
          best == null ? MatchMethod.NONE : MatchMethod.PROBABILISTIC,
          best == null ? null : best.score());
    }
    List<MatchScore> relevant =
        scored.stream()
            .filter(s -> s.score() >= matcher.parameters().thresholdLow())
            .limit(MAX_CASE_CANDIDATES)
            .toList();
    return openProbabilisticCase(reg, parsed, owners, relevant, cls);
  }

  // ---------------------------------------------------------------------
  // Resultados
  // ---------------------------------------------------------------------

  private IdentityResolution createNew(
      CitizenRegistration reg,
      List<IdentifierCodec.Parsed> parsed,
      Map<String, String> owners,
      MatchMethod method,
      Double score) {
    boolean anyInvalid = parsed.stream().anyMatch(p -> !p.valid());
    Citizen c =
        newCitizen(
            reg,
            anyInvalid ? RegistrationState.INCOMPLETE : RegistrationState.VALIDATED,
            IdentityConfidence.CONFIRMED);
    List<CitizenIdentifier> ids = persistIdentifiers(c, parsed, owners, reg.source());
    persistSatellites(reg, c, "created");
    audit.record(
        AuditEntry.of("citizen.created", "citizen", c.id, c.id)
            .withDetails(Map.of("method", method.wire(), "source_system", reg.source().system())));
    events.citizen(
        "created", c, ids, reg.source(), method, MatchClassification.NEW, score, ruleVersion());
    return new IdentityResolution(
        c.id,
        MatchClassification.NEW,
        method,
        score,
        ruleVersion(),
        null,
        RegistrationState.fromWire(c.registrationState));
  }

  private IdentityResolution link(
      CitizenRegistration reg,
      List<IdentifierCodec.Parsed> parsed,
      Map<String, String> owners,
      Citizen target,
      MatchMethod method,
      List<MatchEvidence> evidences) {
    String tenant = tenantContext.require();
    List<CitizenIdentifier> current = identifiers.findByCitizen(target.id);
    for (IdentifierCodec.Parsed p : parsed) {
      boolean present =
          current.stream()
              .anyMatch(ci -> ci.system.equals(p.systemName()) && ci.valueHash.equals(p.hash()));
      if (present) {
        continue;
      }
      String status;
      if (!p.valid()) {
        status = CitizenIdentifier.STATUS_INVALID;
      } else if (owners.containsKey(p.systemName())
          && !owners.get(p.systemName()).equals(target.id)) {
        status = CitizenIdentifier.STATUS_INVALID; // pertence a outro cidadão
      } else {
        status = CitizenIdentifier.STATUS_ACTIVE;
      }
      CitizenIdentifier ci = codec.newEntity(tenant, target.id, p, reg.source().system(), status);
      identifiers.persist(ci);
      current.add(ci);
    }
    if (sourceLinks
        .findBySource(tenant, reg.source().system(), reg.source().sourceRecordId())
        .isEmpty()) {
      sourceLinks.persist(newSourceLink(tenant, target.id, reg.source()));
    }
    List<String> changed = survive(reg, target);
    if (addresses.findCurrent(target.id).isEmpty() && reg.address() != null) {
      addresses.persist(newAddress(tenant, target.id, reg));
    }
    persistMissingContacts(tenant, target, reg);
    if (!changed.isEmpty()) {
      recordHistory(target, reg, "linked:" + String.join(",", changed));
    }
    target.updatedAt = Instant.now();

    CitizenMatchCandidate mc =
        newCandidate(null, target.id, target.id, method, MatchClassification.CONFIRMED, null);
    matchCandidates.persist(mc);
    persistEvidences(mc.id, evidences);

    audit.record(
        AuditEntry.of("citizen.linked", "citizen", target.id, target.id)
            .withDetails(
                Map.of(
                    "method", method.wire(),
                    "source_system", reg.source().system(),
                    "changed_attributes", changed)));
    events.citizen(
        "linked",
        target,
        current,
        reg.source(),
        method,
        MatchClassification.CONFIRMED,
        null,
        ruleVersion());
    return new IdentityResolution(
        target.id,
        MatchClassification.CONFIRMED,
        method,
        null,
        ruleVersion(),
        null,
        RegistrationState.fromWire(target.registrationState));
  }

  private IdentityResolution openConflictCase(
      CitizenRegistration reg,
      List<IdentifierCodec.Parsed> parsed,
      Map<String, String> owners,
      Citizen candidate,
      MatchMethod method,
      ConflictDetector.Result conflicts) {
    Citizen c = newCitizen(reg, RegistrationState.DIVERGENT, IdentityConfidence.DIVERGENT);
    List<CitizenIdentifier> ids = persistIdentifiers(c, parsed, owners, reg.source());
    persistSatellites(reg, c, "created_divergent");

    CitizenMergeCase mergeCase =
        newCase(
            MatchClassification.PENDING,
            null,
            List.of(c.id, candidate.id),
            conflicts.conflicts(),
            "conflito " + method.wire() + ": " + String.join(", ", conflicts.conflicts()));
    mergeCases.persist(mergeCase);
    CitizenMatchCandidate mc =
        newCandidate(mergeCase.id, c.id, candidate.id, method, MatchClassification.PENDING, null);
    matchCandidates.persist(mc);
    persistEvidences(mc.id, conflicts.evidences());

    audit.record(
        AuditEntry.of("citizen.created", "citizen", c.id, c.id)
            .withDetails(Map.of("method", method.wire(), "divergent", true)));
    audit.record(
        AuditEntry.of("merge_case.opened", "merge_case", mergeCase.id, c.id)
            .withDetails(
                Map.of("conflicts", conflicts.conflicts(), "candidates", mergeCase.candidateIds)));
    events.citizen(
        "created", c, ids, reg.source(), method, MatchClassification.PENDING, null, ruleVersion());
    events.merge(
        "case_opened",
        mergeCase,
        candidate.id,
        List.of(c.id),
        identifiers.findByCitizen(candidate.id),
        mergeCase.reason,
        null,
        conflicts.evidences().size());
    return new IdentityResolution(
        c.id,
        MatchClassification.PENDING,
        method,
        null,
        ruleVersion(),
        mergeCase.id,
        RegistrationState.DIVERGENT);
  }

  private IdentityResolution openProbabilisticCase(
      CitizenRegistration reg,
      List<IdentifierCodec.Parsed> parsed,
      Map<String, String> owners,
      List<MatchScore> relevant,
      ProbabilisticMatcher.Classification cls) {
    MatchClassification classification =
        cls == ProbabilisticMatcher.Classification.PROBABLE
            ? MatchClassification.PROBABLE
            : MatchClassification.PENDING;
    Citizen c =
        newCitizen(
            reg,
            RegistrationState.PENDING,
            classification == MatchClassification.PROBABLE
                ? IdentityConfidence.PROBABLE
                : IdentityConfidence.PENDING);
    List<CitizenIdentifier> ids = persistIdentifiers(c, parsed, owners, reg.source());
    persistSatellites(reg, c, "created_pending");

    MatchScore best = relevant.get(0);
    List<String> candidateIds = new ArrayList<>();
    candidateIds.add(c.id);
    relevant.forEach(s -> candidateIds.add(s.candidate().citizenId()));
    CitizenMergeCase mergeCase =
        newCase(
            classification,
            best.score(),
            candidateIds,
            List.of(),
            "correspondência probabilística "
                + classification.wire()
                + " (score "
                + best.score()
                + ")");
    mergeCases.persist(mergeCase);
    int evidenceCount = 0;
    for (MatchScore s : relevant) {
      CitizenMatchCandidate mc =
          newCandidate(
              mergeCase.id,
              c.id,
              s.candidate().citizenId(),
              MatchMethod.PROBABILISTIC,
              matcher.classify(s.score()) == ProbabilisticMatcher.Classification.PROBABLE
                  ? MatchClassification.PROBABLE
                  : MatchClassification.PENDING,
              s.score());
      matchCandidates.persist(mc);
      persistEvidences(mc.id, s.evidences());
      evidenceCount += s.evidences().size();
    }

    audit.record(
        AuditEntry.of("citizen.created", "citizen", c.id, c.id)
            .withDetails(
                Map.of("method", "probabilistic", "classification", classification.wire())));
    audit.record(
        AuditEntry.of("merge_case.opened", "merge_case", mergeCase.id, c.id)
            .withDetails(Map.of("score", best.score(), "candidates", candidateIds)));
    events.citizen(
        "created",
        c,
        ids,
        reg.source(),
        MatchMethod.PROBABILISTIC,
        classification,
        best.score(),
        ruleVersion());
    events.merge(
        "case_opened",
        mergeCase,
        best.candidate().citizenId(),
        List.of(c.id),
        identifiers.findByCitizen(best.candidate().citizenId()),
        mergeCase.reason,
        null,
        evidenceCount);
    return new IdentityResolution(
        c.id,
        classification,
        MatchMethod.PROBABILISTIC,
        best.score(),
        ruleVersion(),
        mergeCase.id,
        RegistrationState.PENDING);
  }

  // ---------------------------------------------------------------------
  // Persistência auxiliar
  // ---------------------------------------------------------------------

  private Citizen newCitizen(
      CitizenRegistration reg, RegistrationState state, IdentityConfidence confidence) {
    CitizenRegistration.Demographics d = reg.demographics();
    Citizen c = new Citizen();
    c.id = Ulid.generate(Ulid.CITIZEN);
    c.tenantId = tenantContext.require();
    c.status = Citizen.STATUS_ACTIVE;
    c.registrationState = state.wire();
    c.identityConfidence = confidence.wire();
    c.legalName = d.legalName().trim();
    c.socialName = blankToNull(d.socialName());
    c.motherName = blankToNull(d.motherName());
    c.fatherName = blankToNull(d.fatherName());
    c.birthdate = d.birthdate();
    c.sex = (d.sex() == null ? Sex.UNKNOWN : d.sex()).wire();
    c.raceColor = blankToNull(d.raceColor());
    c.nationality = blankToNull(d.nationality());
    c.deceased = Boolean.TRUE.equals(d.deceased());
    c.deceasedAt = d.deceasedAt();
    c.normalizedName = NameNormalizer.normalize(c.legalName);
    c.normalizedSocialName = NameNormalizer.normalize(c.socialName);
    c.normalizedMotherName = NameNormalizer.normalize(c.motherName);
    if (reg.territory() != null) {
      c.healthUnitCnes = blankToNull(reg.territory().healthUnitCnes());
      c.teamIne = blankToNull(reg.territory().teamIne());
      c.microarea = blankToNull(reg.territory().microarea());
    }
    citizens.persist(c);
    return c;
  }

  private List<CitizenIdentifier> persistIdentifiers(
      Citizen c,
      List<IdentifierCodec.Parsed> parsed,
      Map<String, String> owners,
      CitizenRegistration.SourceRef source) {
    List<CitizenIdentifier> out = new ArrayList<>();
    for (IdentifierCodec.Parsed p : parsed) {
      String status =
          !p.valid() || owners.containsKey(p.systemName())
              ? CitizenIdentifier.STATUS_INVALID
              : CitizenIdentifier.STATUS_ACTIVE;
      CitizenIdentifier ci = codec.newEntity(c.tenantId, c.id, p, source.system(), status);
      identifiers.persist(ci);
      out.add(ci);
    }
    return out;
  }

  private void persistSatellites(CitizenRegistration reg, Citizen c, String reason) {
    sourceLinks.persist(newSourceLink(c.tenantId, c.id, reg.source()));
    if (reg.address() != null) {
      addresses.persist(newAddress(c.tenantId, c.id, reg));
    }
    persistMissingContacts(c.tenantId, c, reg);
    recordHistory(c, reg, reason);
    for (Map.Entry<String, Object> e : attributes(c).entrySet()) {
      upsertGolden(c, e.getKey(), String.valueOf(e.getValue()), reg.source());
    }
  }

  /** Sobrevivência simples: preenche atributos ausentes no golden record; retorna os alterados. */
  private List<String> survive(CitizenRegistration reg, Citizen target) {
    CitizenRegistration.Demographics d = reg.demographics();
    List<String> changed = new ArrayList<>();
    if (target.socialName == null && blankToNull(d.socialName()) != null) {
      target.socialName = d.socialName().trim();
      target.normalizedSocialName = NameNormalizer.normalize(target.socialName);
      changed.add("social_name");
    }
    if (target.motherName == null && blankToNull(d.motherName()) != null) {
      target.motherName = d.motherName().trim();
      target.normalizedMotherName = NameNormalizer.normalize(target.motherName);
      changed.add("mother_name");
    }
    if (target.fatherName == null && blankToNull(d.fatherName()) != null) {
      target.fatherName = d.fatherName().trim();
      changed.add("father_name");
    }
    if ("unknown".equals(target.sex) && d.sex() != null && d.sex() != Sex.UNKNOWN) {
      target.sex = d.sex().wire();
      changed.add("sex");
    }
    if (target.raceColor == null && blankToNull(d.raceColor()) != null) {
      target.raceColor = d.raceColor().trim();
      changed.add("race_color");
    }
    if (target.nationality == null && blankToNull(d.nationality()) != null) {
      target.nationality = d.nationality().trim();
      changed.add("nationality");
    }
    if (reg.territory() != null) {
      if (target.healthUnitCnes == null && blankToNull(reg.territory().healthUnitCnes()) != null) {
        target.healthUnitCnes = reg.territory().healthUnitCnes().trim();
        changed.add("health_unit_cnes");
      }
      if (target.teamIne == null && blankToNull(reg.territory().teamIne()) != null) {
        target.teamIne = reg.territory().teamIne().trim();
        changed.add("team_ine");
      }
      if (target.microarea == null && blankToNull(reg.territory().microarea()) != null) {
        target.microarea = reg.territory().microarea().trim();
        changed.add("microarea");
      }
    }
    Map<String, Object> attrs = attributes(target);
    for (String attr : changed) {
      upsertGolden(target, attr, String.valueOf(attrs.get(attr)), reg.source());
    }
    return changed;
  }

  private void upsertGolden(
      Citizen c, String attribute, String value, CitizenRegistration.SourceRef source) {
    CitizenGoldenRecordAttribute g =
        goldenRecords
            .findOne(c.id, attribute)
            .orElseGet(
                () -> {
                  CitizenGoldenRecordAttribute n = new CitizenGoldenRecordAttribute();
                  n.id = Ulid.generate(Ulid.GOLDEN_ATTRIBUTE);
                  n.tenantId = c.tenantId;
                  n.citizenId = c.id;
                  n.attribute = attribute;
                  return n;
                });
    g.value = value;
    g.sourceSystem = source.system();
    g.sourceRecordId = source.sourceRecordId();
    g.receivedAt = Instant.now();
    g.confidence = 1.0;
    goldenRecords.persist(g);
  }

  private void recordHistory(Citizen c, CitizenRegistration reg, String reason) {
    Instant now = Instant.now();
    history
        .findCurrent(c.id)
        .ifPresent(
            prev -> {
              prev.recordedTo = now;
              prev.validTo = now;
            });
    CitizenDemographicHistory h = new CitizenDemographicHistory();
    h.id = Ulid.generate(Ulid.HISTORY);
    h.tenantId = c.tenantId;
    h.citizenId = c.id;
    h.attributes = attributes(c);
    h.validFrom = now;
    h.recordedFrom = now;
    h.sourceSystem = reg.source().system();
    h.sourceRecordId = reg.source().sourceRecordId();
    h.changeReason = reason;
    history.persist(h);
  }

  private void persistMissingContacts(String tenant, Citizen c, CitizenRegistration reg) {
    if (reg.contacts() == null) {
      return;
    }
    List<CitizenContact> existing = contacts.findByCitizen(c.id);
    for (CitizenRegistration.ContactInput in : reg.contacts()) {
      if (in == null || in.kind() == null || in.value() == null || in.value().isBlank()) {
        continue;
      }
      String kind = in.kind().toLowerCase();
      if (!List.of("phone", "mobile", "email").contains(kind)) {
        throw DomainValidationException.field("contacts.kind", "deve ser phone, mobile ou email");
      }
      String norm = ContactNormalizer.normalize(kind, in.value());
      if (norm == null || existing.stream().anyMatch(e -> e.valueNorm.equals(norm))) {
        continue;
      }
      CitizenContact ct = new CitizenContact();
      ct.id = Ulid.generate(Ulid.CONTACT);
      ct.tenantId = tenant;
      ct.citizenId = c.id;
      ct.kind = kind;
      ct.value = in.value().trim();
      ct.valueNorm = norm;
      ct.valueMasked = "email".equals(kind) ? Masks.email(ct.value) : Masks.phone(ct.value);
      ct.preferred = existing.isEmpty();
      ct.sourceSystem = reg.source().system();
      contacts.persist(ct);
      existing.add(ct);
    }
  }

  private CitizenMergeCase newCase(
      MatchClassification classification,
      Double score,
      List<String> candidateIds,
      List<String> conflicts,
      String reason) {
    CitizenMergeCase mc = new CitizenMergeCase();
    mc.id = Ulid.generate(Ulid.MERGE_CASE);
    mc.tenantId = tenantContext.require();
    mc.status = MergeCaseStatus.OPEN.wire();
    mc.classification = classification.wire();
    mc.score = score;
    mc.ruleVersion = ruleVersion();
    mc.candidateIds = List.copyOf(candidateIds);
    mc.conflicts = List.copyOf(conflicts);
    mc.reason = reason;
    return mc;
  }

  private CitizenMatchCandidate newCandidate(
      String caseId,
      String incomingId,
      String candidateId,
      MatchMethod method,
      MatchClassification classification,
      Double score) {
    CitizenMatchCandidate mc = new CitizenMatchCandidate();
    mc.id = Ulid.generate(Ulid.MATCH_CANDIDATE);
    mc.tenantId = tenantContext.require();
    mc.caseId = caseId;
    mc.incomingCitizenId = incomingId;
    mc.candidateCitizenId = candidateId;
    mc.method = method.wire();
    mc.classification = classification.wire();
    mc.score = score;
    mc.ruleVersion = ruleVersion();
    return mc;
  }

  private void persistEvidences(String candidateId, List<MatchEvidence> evidences) {
    for (MatchEvidence e : evidences) {
      CitizenMatchEvidence me = new CitizenMatchEvidence();
      me.id = Ulid.generate(Ulid.MATCH_EVIDENCE);
      me.tenantId = tenantContext.require();
      me.matchCandidateId = candidateId;
      me.attribute = e.attribute();
      me.comparison = e.comparison();
      me.agreement = e.agreement().wire();
      me.weight = e.weight();
      matchEvidences.persist(me);
    }
  }

  private static CitizenSourceLink newSourceLink(
      String tenant, String citizenId, CitizenRegistration.SourceRef s) {
    CitizenSourceLink link = new CitizenSourceLink();
    link.id = Ulid.generate(Ulid.SOURCE_LINK);
    link.tenantId = tenant;
    link.citizenId = citizenId;
    link.sourceSystem = s.system();
    link.connector = s.connector();
    link.sourceRecordId = s.sourceRecordId();
    link.sourceRecordVersion = s.sourceRecordVersion();
    link.cnes = blankToNull(s.cnes());
    return link;
  }

  private static CitizenAddress newAddress(
      String tenant, String citizenId, CitizenRegistration reg) {
    CitizenRegistration.AddressInput a = reg.address();
    CitizenAddress addr = new CitizenAddress();
    addr.id = Ulid.generate(Ulid.ADDRESS);
    addr.tenantId = tenant;
    addr.citizenId = citizenId;
    addr.street = a.street();
    addr.number = a.number();
    addr.complement = a.complement();
    addr.district = a.district();
    addr.cityIbge = a.cityIbge();
    addr.postalCode = a.postalCode() == null ? null : a.postalCode().replaceAll("[^0-9]", "");
    addr.sourceSystem = reg.source().system();
    return addr;
  }

  /** Atributos demográficos atuais (snapshot para histórico e proveniência). */
  static Map<String, Object> attributes(Citizen c) {
    Map<String, Object> m = new LinkedHashMap<>();
    put(m, "legal_name", c.legalName);
    put(m, "social_name", c.socialName);
    put(m, "mother_name", c.motherName);
    put(m, "father_name", c.fatherName);
    put(m, "birthdate", c.birthdate == null ? null : c.birthdate.toString());
    put(m, "sex", c.sex);
    put(m, "race_color", c.raceColor);
    put(m, "nationality", c.nationality);
    put(m, "health_unit_cnes", c.healthUnitCnes);
    put(m, "team_ine", c.teamIne);
    put(m, "microarea", c.microarea);
    return m;
  }

  private static void put(Map<String, Object> m, String k, Object v) {
    if (v != null) {
      m.put(k, v);
    }
  }

  private String resolveOwnerId(String citizenId) {
    Citizen c = citizens.findById(citizenId);
    return c == null ? citizenId : citizens.resolveMerged(c).id;
  }

  private String ruleVersion() {
    return matcher.parameters().ruleVersion();
  }

  private static void validate(CitizenRegistration reg) {
    if (reg == null || reg.demographics() == null || reg.source() == null) {
      throw new DomainValidationException("source e demographics são obrigatórios");
    }
    LocalDate bd = reg.demographics().birthdate();
    if (bd == null) {
      throw DomainValidationException.field("demographics.birthdate", "obrigatória");
    }
    if (bd.isAfter(LocalDate.now()) || bd.isBefore(LocalDate.of(1890, 1, 1))) {
      throw DomainValidationException.field("demographics.birthdate", "data implausível");
    }
    if (NameNormalizer.normalize(reg.demographics().legalName()) == null) {
      throw DomainValidationException.field("demographics.legal_name", "nome inválido");
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
