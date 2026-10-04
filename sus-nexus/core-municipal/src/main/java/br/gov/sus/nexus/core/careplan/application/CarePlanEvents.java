package br.gov.sus.nexus.core.careplan.application;

import br.gov.sus.nexus.core.careplan.domain.CareGap;
import br.gov.sus.nexus.core.careplan.domain.CarePlan;
import br.gov.sus.nexus.core.careplan.domain.CarePlanItem;
import br.gov.sus.nexus.core.platform.events.DomainEvent;
import br.gov.sus.nexus.core.platform.events.EventEnvelope;
import br.gov.sus.nexus.core.platform.events.EventPublisher;
import br.gov.sus.nexus.core.platform.security.Purpose;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publica {@code sus.careplan.*} ({@code careplan/careplan.v1.schema.json}) e {@code sus.caregap.*}
 * ({@code caregap/caregap.v1.schema.json}), ambos {@code restricted}: só ids, códigos e contagens.
 */
@ApplicationScoped
public class CarePlanEvents {

  public static final String PLAN_PREFIX = "sus.careplan.";
  public static final String GAP_PREFIX = "sus.caregap.";
  public static final String PLAN_AGGREGATE = "care_plan";
  public static final String GAP_AGGREGATE = "care_gap";
  static final String EVENT_VERSION = "1.0";
  static final List<String> PURPOSES = List.of(Purpose.CARE_COORDINATION.wire());

  @Inject EventPublisher publisher;

  public String publishPlan(
      String action, CarePlan p, List<CarePlanItem> items, String causationId) {
    Instant now = Instant.now();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("care_plan_id", p.id);
    data.put("care_line", p.careLine);
    data.put("status", p.status);
    data.put("protocol_id", p.protocolId);
    data.put("protocol_version", p.protocolVersion);
    cnes(data, "health_unit_cnes", p.healthUnitCnes);
    put(data, "team_ine", p.teamIne);
    if (p.originKind != null) {
      Map<String, Object> origin = new LinkedHashMap<>();
      origin.put("kind", p.originKind);
      put(origin, "id", p.originId);
      data.put("origin", origin);
    }
    data.put("items_total", items.size());
    data.put("items_due", items.stream().filter(CarePlanItem::isOpen).count());
    data.put(
        "items_overdue",
        items.stream()
            .filter(i -> i.isOpen() && i.expectedBy != null && i.expectedBy.isBefore(now))
            .count());
    put(data, "closed_reason", p.closedReason);
    return publisher.publish(
        new DomainEvent(
            PLAN_AGGREGATE,
            p.id,
            PLAN_PREFIX + action,
            EVENT_VERSION,
            OffsetDateTime.now(ZoneOffset.UTC),
            new EventEnvelope.Subject(p.citizenId, null),
            source(p.id),
            data,
            DomainEvent.restricted(PURPOSES),
            causationId));
  }

  public String publishGap(String action, CareGap g, String causationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("care_gap_id", g.id);
    put(data, "care_plan_id", g.carePlanId);
    data.put("care_line", g.careLine);
    data.put("gap_kind", g.gapKind);
    if (g.expectedBy != null) {
      data.put("expected_by", utc(g.expectedBy));
      data.put("days_overdue", CarePlanServiceImpl.daysOverdue(g.expectedBy, Instant.now()));
    }
    put(data, "protocol_id", g.protocolId);
    data.put("protocol_version", g.protocolVersion);
    cnes(data, "health_unit_cnes", g.healthUnitCnes);
    put(data, "team_ine", g.teamIne);
    put(data, "microarea", g.microarea);
    data.put("detected_at", utc(g.detectedAt));
    if (g.resolvedAt != null) {
      data.put("resolved_at", utc(g.resolvedAt));
    }
    put(data, "resolution", g.resolution);
    put(data, "task_id", g.taskId);
    return publisher.publish(
        new DomainEvent(
            GAP_AGGREGATE,
            g.id,
            GAP_PREFIX + action,
            EVENT_VERSION,
            OffsetDateTime.now(ZoneOffset.UTC),
            new EventEnvelope.Subject(g.citizenId, null),
            source(g.id),
            data,
            DomainEvent.restricted(PURPOSES),
            causationId));
  }

  private static EventEnvelope.Source source(String id) {
    return new EventEnvelope.Source("core-municipal", "core-municipal", id, null, null);
  }

  private static OffsetDateTime utc(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }

  private static void cnes(Map<String, Object> m, String k, String v) {
    if (Cnes.isValid(v)) {
      m.put(k, v);
    }
  }

  private static void put(Map<String, Object> m, String k, String v) {
    if (v != null && !v.isBlank()) {
      m.put(k, v);
    }
  }
}
