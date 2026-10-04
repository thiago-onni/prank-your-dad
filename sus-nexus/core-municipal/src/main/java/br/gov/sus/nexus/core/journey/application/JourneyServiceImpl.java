package br.gov.sus.nexus.core.journey.application;

import br.gov.sus.nexus.core.careplan.api.CarePlanService;
import br.gov.sus.nexus.core.consent.api.ConsentService;
import br.gov.sus.nexus.core.exams.api.ExamService;
import br.gov.sus.nexus.core.hospital.api.HospitalService;
import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.CitizenService;
import br.gov.sus.nexus.core.journey.api.CitizenOperationalSummary;
import br.gov.sus.nexus.core.journey.api.JourneyService;
import br.gov.sus.nexus.core.journey.api.TimelineEventDto;
import br.gov.sus.nexus.core.journey.domain.TimelineEvent;
import br.gov.sus.nexus.core.journey.infrastructure.TimelineEventRepository;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.AuthorizationPolicy;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.regulation.api.RegulationService;
import br.gov.sus.nexus.core.scheduling.api.AppointmentService;
import br.gov.sus.nexus.core.tasks.api.TaskQueries;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Linha do tempo com filtragem por perfil no servidor (JOR-006): cada par (domínio, sensibilidade)
 * é avaliado pela {@link AuthorizationPolicy}; eventos negados são omitidos e as obrigações de
 * redação são aplicadas. Resumo operacional (JOR-008) composto pelas APIs públicas dos módulos.
 */
@ApplicationScoped
public class JourneyServiceImpl implements JourneyService {

  static final Set<String> DOMAINS =
      Set.of(
          "identity",
          "aps",
          "schedule",
          "regulation",
          "exam",
          "hospital",
          "careplan",
          "task",
          "production",
          "communication");

  @Inject TimelineEventRepository repository;
  @Inject AuthorizationPolicy policy;
  @Inject CurrentActor currentActor;
  @Inject TenantContext tenantContext;
  @Inject CitizenService citizens;
  @Inject AppointmentService appointments;
  @Inject TaskQueries tasks;
  @Inject RegulationService regulation;
  @Inject ExamService exams;
  @Inject HospitalService hospital;
  @Inject CarePlanService carePlans;
  @Inject ConsentService consent;

  @Override
  @TenantTransactional
  public Page<TimelineEventDto> timeline(
      String citizenId,
      OffsetDateTime from,
      OffsetDateTime to,
      List<String> domains,
      String cnes,
      String status,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    if (domains != null) {
      for (String d : domains) {
        if (!DOMAINS.contains(d)) {
          throw DomainValidationException.field("domain", "domínio desconhecido: " + d);
        }
      }
    }
    Instant beforeAt = null;
    String beforeId = null;
    String decoded = Cursor.decode(cursor).orElse(null);
    if (decoded != null) {
      int bar = decoded.indexOf('|');
      if (bar <= 0) {
        throw DomainValidationException.field("cursor", "cursor inválido");
      }
      beforeAt = Instant.parse(decoded.substring(0, bar));
      beforeId = decoded.substring(bar + 1);
    }

    Map<String, AuthorizationPolicy.Decision> cache = new HashMap<>();
    List<TimelineEventDto> items = new ArrayList<>();
    TimelineEvent lastIncluded = null;
    boolean more = false;
    int fetch = size + 1;
    // busca em páginas sucessivas até preencher `size` itens permitidos (ou esgotar)
    outer:
    while (true) {
      List<TimelineEvent> rows =
          repository.timeline(
              citizenId,
              from == null ? null : from.toInstant(),
              to == null ? null : to.toInstant(),
              domains,
              blank(cnes),
              blank(status),
              beforeAt,
              beforeId,
              fetch);
      if (rows.isEmpty()) {
        break;
      }
      for (TimelineEvent t : rows) {
        AuthorizationPolicy.Decision decision =
            cache.computeIfAbsent(t.domain + "|" + t.sensitivity, k -> decide(citizenId, t));
        if (!decision.allowed()) {
          continue;
        }
        if (items.size() == size) {
          more = true;
          break outer;
        }
        items.add(toDto(t).redacted(decision.obligations().redactFields()));
        lastIncluded = t;
      }
      if (rows.size() < fetch) {
        break;
      }
      TimelineEvent last = rows.get(rows.size() - 1);
      beforeAt = last.occurredAt;
      beforeId = last.id;
    }
    String nextCursor =
        more && lastIncluded != null
            ? Cursor.encode(lastIncluded.occurredAt.toString() + "|" + lastIncluded.id)
            : null;
    return new Page<>(List.copyOf(items), nextCursor);
  }

  private AuthorizationPolicy.Decision decide(String citizenId, TimelineEvent t) {
    Map<String, Object> attrs = new HashMap<>();
    attrs.put("domain", t.domain);
    attrs.put("sensitivity", t.sensitivity);
    attrs.put("citizen_id", citizenId);
    return policy.evaluate(
        new AuthorizationPolicy.Input(
            tenantContext.require(),
            currentActor.actorId(),
            currentActor.roles(),
            "timeline:read",
            "timeline_event",
            t.id,
            currentActor.purpose().orElse(null),
            attrs));
  }

  @Override
  @TenantTransactional
  public CitizenOperationalSummary summary(String citizenId) {
    CitizenDetail citizen = citizens.get(citizenId);
    // linhas de cuidado: planos ativos (careplan) + linhas observadas nos eventos
    LinkedHashSet<String> careLines = new LinkedHashSet<>(carePlans.activeCareLines(citizenId));
    careLines.addAll(repository.careLines(citizenId));
    return new CitizenOperationalSummary(
        citizen.id(),
        repository.lastOccurred(citizenId, "aps").map(i -> i.atOffset(ZoneOffset.UTC)).orElse(null),
        appointments.nextAppointmentAt(citizenId).orElse(null),
        tasks.countOpen(citizenId),
        regulation.countOpen(citizenId),
        exams.countPending(citizenId),
        hospital.lastDischargeAt(citizenId).orElse(null),
        List.copyOf(careLines),
        carePlans.countOpenGaps(citizenId),
        consent.contactValid(citizenId));
  }

  static TimelineEventDto toDto(TimelineEvent t) {
    return new TimelineEventDto(
        t.id,
        t.citizenId,
        t.domain,
        t.eventType,
        t.occurredAt.atOffset(ZoneOffset.UTC),
        t.recordedAt.atOffset(ZoneOffset.UTC),
        t.sourceSystem,
        t.cnes,
        t.healthUnitName,
        t.professionalRef,
        t.status,
        t.confidence,
        t.sensitivity,
        t.summary,
        t.detailRef,
        t.correlationChain == null ? List.of() : List.of(t.correlationChain));
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
