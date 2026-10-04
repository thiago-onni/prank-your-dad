package br.gov.sus.nexus.core.identity.application;

import br.gov.sus.nexus.core.identity.api.CitizenRegistration;
import br.gov.sus.nexus.core.identity.api.MatchClassification;
import br.gov.sus.nexus.core.identity.api.MatchMethod;
import br.gov.sus.nexus.core.identity.domain.Citizen;
import br.gov.sus.nexus.core.identity.domain.CitizenIdentifier;
import br.gov.sus.nexus.core.identity.domain.CitizenMergeCase;
import br.gov.sus.nexus.core.platform.events.DomainEvent;
import br.gov.sus.nexus.core.platform.events.EventEnvelope;
import br.gov.sus.nexus.core.platform.events.EventPublisher;
import br.gov.sus.nexus.core.platform.security.Purpose;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monta e publica (via outbox) os eventos {@code sus.identity.citizen.*} e {@code
 * sus.identity.merge.*}. Subject carrega APENAS identificadores mascarados/hash.
 */
@ApplicationScoped
public class IdentityEvents {

  public static final String CITIZEN_TOPIC_PREFIX = "sus.identity.citizen.";
  public static final String MERGE_TOPIC_PREFIX = "sus.identity.merge.";
  static final String EVENT_VERSION = "1.0";

  @Inject EventPublisher publisher;

  public String citizen(
      String action,
      Citizen c,
      List<CitizenIdentifier> identifiers,
      CitizenRegistration.SourceRef source,
      MatchMethod method,
      MatchClassification classification,
      Double score,
      String ruleVersion) {
    Map<String, Object> match = new LinkedHashMap<>();
    match.put("method", method.wire());
    match.put("classification", classification.wire());
    if (score != null) {
      match.put("score", score);
    }
    match.put("rule_version", ruleVersion);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("municipal_citizen_id", c.id);
    data.put("registration_state", c.registrationState);
    data.put("match", match);
    data.put(
        "linked_identifier_systems",
        identifiers.stream()
            .filter(i -> CitizenIdentifier.STATUS_ACTIVE.equals(i.status))
            .map(i -> i.system)
            .distinct()
            .toList());
    Map<String, Object> territory = new LinkedHashMap<>();
    if (Cnes.isValid(c.healthUnitCnes)) {
      territory.put("health_unit_cnes", c.healthUnitCnes);
    }
    if (c.teamIne != null) {
      territory.put("team_ine", c.teamIne);
    }
    if (c.microarea != null) {
      territory.put("microarea", c.microarea);
    }
    if (!territory.isEmpty()) {
      data.put("territory", territory);
    }

    return publisher.publish(
        new DomainEvent(
            "citizen",
            c.id,
            CITIZEN_TOPIC_PREFIX + action,
            EVENT_VERSION,
            OffsetDateTime.now(ZoneOffset.UTC),
            subject(c.id, identifiers),
            source(source),
            data,
            DomainEvent.restricted(
                List.of(Purpose.IDENTITY_MANAGEMENT.wire(), Purpose.CARE_COORDINATION.wire())),
            null));
  }

  public String merge(
      String action,
      CitizenMergeCase mergeCase,
      String survivingCitizenId,
      List<String> mergedCitizenIds,
      List<CitizenIdentifier> survivingIdentifiers,
      String reason,
      String decidedBy,
      int evidenceCount) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("case_id", mergeCase.id);
    data.put("surviving_citizen_id", survivingCitizenId);
    data.put("merged_citizen_ids", mergedCitizenIds);
    if (reason != null) {
      data.put("reason", reason.length() > 500 ? reason.substring(0, 500) : reason);
    }
    if (decidedBy != null) {
      data.put("decided_by", decidedBy);
    }
    data.put("evidence_count", evidenceCount);
    data.put("reversible", true);

    return publisher.publish(
        new DomainEvent(
            "merge_case",
            mergeCase.id,
            MERGE_TOPIC_PREFIX + action,
            EVENT_VERSION,
            OffsetDateTime.now(ZoneOffset.UTC),
            subject(survivingCitizenId, survivingIdentifiers),
            new EventEnvelope.Source("core-municipal", "core-municipal", mergeCase.id, null, null),
            data,
            DomainEvent.restricted(List.of(Purpose.IDENTITY_MANAGEMENT.wire())),
            null));
  }

  static EventEnvelope.Subject subject(String citizenId, List<CitizenIdentifier> identifiers) {
    List<EventEnvelope.SubjectIdentifier> ids = new ArrayList<>();
    for (CitizenIdentifier ci : identifiers) {
      if (CitizenIdentifier.STATUS_ACTIVE.equals(ci.status)
          && ("CNS".equals(ci.system) || "CPF".equals(ci.system))) {
        ids.add(new EventEnvelope.SubjectIdentifier(ci.system, ci.valueMasked, ci.valueHash));
      }
    }
    return new EventEnvelope.Subject(citizenId, ids.isEmpty() ? null : ids);
  }

  static EventEnvelope.Source source(CitizenRegistration.SourceRef s) {
    return new EventEnvelope.Source(
        s.system(),
        s.connector(),
        s.sourceRecordId(),
        s.sourceRecordVersion(),
        Cnes.isValid(s.cnes()) ? s.cnes().trim() : null);
  }
}
