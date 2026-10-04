package br.gov.sus.nexus.core.identity.application;

import br.gov.sus.nexus.core.audit.api.AuditEntry;
import br.gov.sus.nexus.core.audit.api.AuditService;
import br.gov.sus.nexus.core.identity.api.CitizenSummary;
import br.gov.sus.nexus.core.identity.api.IdentityConfidence;
import br.gov.sus.nexus.core.identity.api.MergeCaseDto;
import br.gov.sus.nexus.core.identity.api.MergeCaseService;
import br.gov.sus.nexus.core.identity.api.MergeCaseStatus;
import br.gov.sus.nexus.core.identity.api.RegistrationState;
import br.gov.sus.nexus.core.identity.api.Requests;
import br.gov.sus.nexus.core.identity.domain.Citizen;
import br.gov.sus.nexus.core.identity.domain.CitizenIdentifier;
import br.gov.sus.nexus.core.identity.domain.CitizenMatchCandidate;
import br.gov.sus.nexus.core.identity.domain.CitizenMatchEvidence;
import br.gov.sus.nexus.core.identity.domain.CitizenMerge;
import br.gov.sus.nexus.core.identity.domain.CitizenMergeCase;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenIdentifierRepository;
import br.gov.sus.nexus.core.identity.infrastructure.CitizenRepository;
import br.gov.sus.nexus.core.identity.infrastructure.MatchCandidateRepository;
import br.gov.sus.nexus.core.identity.infrastructure.MatchEvidenceRepository;
import br.gov.sus.nexus.core.identity.infrastructure.MergeCaseRepository;
import br.gov.sus.nexus.core.identity.infrastructure.MergeRepository;
import br.gov.sus.nexus.core.platform.errors.ConflictException;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fila de revisão do MPI. Merge NÃO apaga: marca {@code status=merged} + {@code merged_into_id} e
 * guarda snapshot para unmerge. Toda decisão exige justificativa, gera audit_log e evento.
 */
@ApplicationScoped
public class MergeCaseServiceImpl implements MergeCaseService {

  @Inject MergeCaseRepository cases;
  @Inject MergeRepository merges;
  @Inject CitizenRepository citizens;
  @Inject CitizenIdentifierRepository identifiers;
  @Inject MatchCandidateRepository candidates;
  @Inject MatchEvidenceRepository evidences;
  @Inject CitizenMapper mapper;
  @Inject IdentityEvents events;
  @Inject AuditService audit;
  @Inject CurrentActor currentActor;
  @Inject TenantContext tenantContext;

  @Override
  @TenantTransactional
  public Page<MergeCaseDto> list(MergeCaseStatus status, String cursor, Integer limit) {
    int size = Cursor.limit(limit);
    String beforeId = Cursor.decode(cursor).orElse(null);
    List<MergeCaseDto> rows =
        cases.list(status == null ? null : status.wire(), beforeId, size + 1).stream()
            .map(this::toDto)
            .toList();
    return Page.of(rows, size, MergeCaseDto::id);
  }

  @Override
  @TenantTransactional
  public MergeCaseDto get(String caseId) {
    return toDto(load(caseId));
  }

  @Override
  @TenantTransactional
  public MergeCaseDto merge(String caseId, Requests.Merge req) {
    CitizenMergeCase mc = load(caseId);
    if (!mc.isOpen()) {
      throw new ConflictException("caso não está aberto: " + mc.status);
    }
    if (!mc.candidateIds.contains(req.survivingCitizenId())) {
      throw DomainValidationException.field(
          "surviving_citizen_id", "deve ser um dos candidatos do caso");
    }
    Citizen surviving = citizens.findById(req.survivingCitizenId());
    if (surviving == null || surviving.isMerged()) {
      throw new ConflictException("cidadão sobrevivente inválido");
    }
    List<String> mergedIds = new ArrayList<>();
    Map<String, Object> snapshot = new LinkedHashMap<>();
    Instant now = Instant.now();
    for (String id : mc.candidateIds) {
      if (id.equals(surviving.id)) {
        continue;
      }
      Citizen other = citizens.findById(id);
      if (other == null) {
        throw new NotFoundException("cidadão", id);
      }
      if (other.isMerged()) {
        throw new ConflictException("cidadão já fundido: " + id);
      }
      snapshot.put(id, snapshotOf(other));
      other.status = Citizen.STATUS_MERGED;
      other.mergedIntoId = surviving.id;
      other.registrationState = RegistrationState.DUPLICATE.wire();
      other.updatedAt = now;
      mergedIds.add(id);
    }
    snapshot.put(surviving.id, snapshotOf(surviving));
    if (RegistrationState.PENDING.wire().equals(surviving.registrationState)
        || RegistrationState.DIVERGENT.wire().equals(surviving.registrationState)) {
      surviving.registrationState = RegistrationState.VALIDATED.wire();
    }
    surviving.identityConfidence = IdentityConfidence.CONFIRMED.wire();
    surviving.updatedAt = now;

    CitizenMerge merge = new CitizenMerge();
    merge.id = Ulid.generate(Ulid.MERGE);
    merge.tenantId = tenantContext.require();
    merge.caseId = mc.id;
    merge.survivingCitizenId = surviving.id;
    merge.mergedCitizenIds = List.copyOf(mergedIds);
    merge.snapshot = snapshot;
    merge.reason = req.reason();
    merge.decidedBy = currentActor.actorId();
    merges.persist(merge);

    mc.status = MergeCaseStatus.MERGED.wire();
    mc.decidedAt = now;
    mc.decidedBy = currentActor.actorId();
    mc.decisionReason = req.reason();
    mc.mergeId = merge.id;
    mc.updatedAt = now;

    int evidenceCount = evidenceCount(mc.id);
    audit.record(
        AuditEntry.of("citizen.merged", "merge_case", mc.id, surviving.id)
            .withReason(req.reason())
            .withDetails(Map.of("merge_id", merge.id, "merged_citizen_ids", mergedIds)));
    events.merge(
        "merged",
        mc,
        surviving.id,
        mergedIds,
        identifiers.findByCitizen(surviving.id),
        req.reason(),
        currentActor.actorId(),
        evidenceCount);
    return toDto(mc);
  }

  @Override
  @TenantTransactional
  public MergeCaseDto reject(String caseId, Requests.Reason req) {
    CitizenMergeCase mc = load(caseId);
    if (!mc.isOpen()) {
      throw new ConflictException("caso não está aberto: " + mc.status);
    }
    Instant now = Instant.now();
    for (String id : mc.candidateIds) {
      Citizen c = citizens.findById(id);
      if (c != null
          && !c.isMerged()
          && RegistrationState.PENDING.wire().equals(c.registrationState)) {
        boolean anyInvalid =
            identifiers.findByCitizen(id).stream()
                .anyMatch(ci -> CitizenIdentifier.STATUS_INVALID.equals(ci.status));
        c.registrationState =
            (anyInvalid ? RegistrationState.INCOMPLETE : RegistrationState.VALIDATED).wire();
        c.identityConfidence = IdentityConfidence.CONFIRMED.wire();
        c.updatedAt = now;
      }
    }
    mc.status = MergeCaseStatus.REJECTED.wire();
    mc.decidedAt = now;
    mc.decidedBy = currentActor.actorId();
    mc.decisionReason = req.reason();
    mc.updatedAt = now;

    String first = mc.candidateIds.get(0);
    audit.record(
        AuditEntry.of("merge_case.rejected", "merge_case", mc.id, first).withReason(req.reason()));
    events.merge(
        "rejected",
        mc,
        first,
        mc.candidateIds.subList(1, mc.candidateIds.size()),
        identifiers.findByCitizen(first),
        req.reason(),
        currentActor.actorId(),
        evidenceCount(mc.id));
    return toDto(mc);
  }

  @Override
  @TenantTransactional
  public MergeCaseDto unmerge(String mergeId, Requests.Reason req) {
    CitizenMerge merge = merges.findById(mergeId);
    if (merge == null) {
      throw new NotFoundException("fusão", mergeId);
    }
    if (merge.unmergedAt != null) {
      throw new ConflictException("fusão já revertida");
    }
    CitizenMergeCase mc = load(merge.caseId);
    Instant now = Instant.now();
    for (String id : merge.mergedCitizenIds) {
      Citizen c = citizens.findById(id);
      if (c == null) {
        throw new NotFoundException("cidadão", id);
      }
      restore(c, merge.snapshot.get(id), now);
    }
    Citizen surviving = citizens.findById(merge.survivingCitizenId);
    if (surviving != null) {
      restore(surviving, merge.snapshot.get(surviving.id), now);
    }
    merge.unmergedAt = now;
    merge.unmergedBy = currentActor.actorId();
    merge.unmergeReason = req.reason();

    mc.status = MergeCaseStatus.UNMERGED.wire();
    mc.decidedAt = now;
    mc.decidedBy = currentActor.actorId();
    mc.decisionReason = req.reason();
    mc.updatedAt = now;

    audit.record(
        AuditEntry.of("citizen.unmerged", "merge_case", mc.id, merge.survivingCitizenId)
            .withReason(req.reason())
            .withDetails(
                Map.of("merge_id", merge.id, "restored_citizen_ids", merge.mergedCitizenIds)));
    events.merge(
        "unmerged",
        mc,
        merge.survivingCitizenId,
        merge.mergedCitizenIds,
        identifiers.findByCitizen(merge.survivingCitizenId),
        req.reason(),
        currentActor.actorId(),
        evidenceCount(mc.id));
    return toDto(mc);
  }

  // ---------------------------------------------------------------------

  private CitizenMergeCase load(String caseId) {
    CitizenMergeCase mc = cases.findById(caseId);
    if (mc == null) {
      throw new NotFoundException("caso de fusão", caseId);
    }
    return mc;
  }

  @SuppressWarnings("unchecked")
  private static void restore(Citizen c, Object snapshot, Instant now) {
    if (!(snapshot instanceof Map<?, ?> m)) {
      return;
    }
    Map<String, Object> s = (Map<String, Object>) m;
    c.status = String.valueOf(s.getOrDefault("status", Citizen.STATUS_ACTIVE));
    Object mergedInto = s.get("merged_into_id");
    c.mergedIntoId = mergedInto == null ? null : mergedInto.toString();
    c.registrationState =
        String.valueOf(s.getOrDefault("registration_state", RegistrationState.PENDING.wire()));
    c.identityConfidence =
        String.valueOf(s.getOrDefault("identity_confidence", IdentityConfidence.PENDING.wire()));
    c.updatedAt = now;
  }

  private static Map<String, Object> snapshotOf(Citizen c) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("status", c.status);
    s.put("merged_into_id", c.mergedIntoId);
    s.put("registration_state", c.registrationState);
    s.put("identity_confidence", c.identityConfidence);
    return s;
  }

  private int evidenceCount(String caseId) {
    List<String> ids = candidates.findByCase(caseId).stream().map(mc -> mc.id).toList();
    return evidences.findByCandidates(ids).size();
  }

  MergeCaseDto toDto(CitizenMergeCase mc) {
    List<CitizenSummary> cands = new ArrayList<>();
    for (String id : mc.candidateIds) {
      Citizen c = citizens.findById(id);
      if (c != null) {
        cands.add(mapper.summary(c));
      }
    }
    List<CitizenMatchCandidate> pairs = candidates.findByCase(mc.id);
    List<CitizenMatchEvidence> evs =
        evidences.findByCandidates(pairs.stream().map(p -> p.id).toList());
    List<MergeCaseDto.Evidence> evidenceDtos =
        evs.stream()
            .map(e -> new MergeCaseDto.Evidence(e.attribute, e.comparison, e.agreement, e.weight))
            .toList();
    return new MergeCaseDto(
        mc.id,
        MergeCaseStatus.fromWire(mc.status),
        mc.reason,
        mc.score,
        mc.ruleVersion,
        cands,
        evidenceDtos,
        mc.conflicts,
        mc.openedAt.atOffset(ZoneOffset.UTC),
        mc.decidedAt == null ? null : mc.decidedAt.atOffset(ZoneOffset.UTC),
        mc.decidedBy,
        mc.decisionReason,
        mc.mergeId);
  }
}
