package br.gov.sus.nexus.core.regulation.application;

import br.gov.sus.nexus.core.platform.events.DomainEvent;
import br.gov.sus.nexus.core.platform.events.EventEnvelope;
import br.gov.sus.nexus.core.platform.events.EventPublisher;
import br.gov.sus.nexus.core.platform.security.Purpose;
import br.gov.sus.nexus.core.regulation.api.RegulationRequestRegistration;
import br.gov.sus.nexus.core.regulation.domain.RegulationRequest;
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
 * Publica {@code sus.regulation.request.*} ({@code regulation/request.v1.schema.json}) e {@code
 * sus.regulation.status.changed} ({@code regulation/status.v1.schema.json}). Payload mínimo: nunca
 * justificativa clínica, CID ou dados do cidadão além do id municipal. Classificação {@code
 * restricted} (domínio regulation).
 */
@ApplicationScoped
public class RegulationEvents {

  public static final String REQUEST_PREFIX = "sus.regulation.request.";
  public static final String STATUS_PREFIX = "sus.regulation.status.";
  public static final String REQUEST_AGGREGATE = "regulation_request";
  public static final String STATUS_AGGREGATE = "regulation_status";
  static final String EVENT_VERSION = "1.0";
  static final List<String> PURPOSES =
      List.of(Purpose.REGULATION.wire(), Purpose.CARE_COORDINATION.wire());

  @Inject EventPublisher publisher;

  public String publishRequest(
      String action,
      RegulationRequest r,
      RegulationRequestRegistration.SourceRef source,
      OffsetDateTime occurredAt,
      String reason,
      String causationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("regulation_request_id", r.id);
    data.put("kind", r.kind);
    data.put("status", r.status);
    data.put("priority", r.priority);
    data.put("requested_service_code", r.requestedServiceCode);
    data.put("code_system", r.codeSystem);
    put(data, "specialty", r.specialty);
    data.put("requested_at", utc(r.requestedAt));
    cnes(data, "requesting_cnes", r.requestingCnes);
    put(data, "requesting_professional_id", r.requestingProfessionalId);
    cnes(data, "provider_cnes", r.providerCnes);
    if (r.justificationPresent != null) {
      data.put("justification_present", r.justificationPresent);
    }
    if (r.attachedDocumentsCount != null) {
      data.put("attached_documents_count", r.attachedDocumentsCount);
    }
    if (r.slaDueAt != null) {
      data.put("sla_due_at", utc(r.slaDueAt));
    }
    put(data, "reason", truncate(reason));
    return publisher.publish(
        new DomainEvent(
            REQUEST_AGGREGATE,
            r.id,
            REQUEST_PREFIX + action,
            EVENT_VERSION,
            occurredAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : occurredAt,
            new EventEnvelope.Subject(r.citizenId, null),
            source(source, r),
            data,
            DomainEvent.restricted(PURPOSES),
            causationId));
  }

  public String publishStatusChanged(
      RegulationRequest r,
      String previousStatus,
      String actorKind,
      String actorId,
      RegulationRequestRegistration.SourceRef source,
      OffsetDateTime occurredAt,
      String reason,
      Boolean returnToOrigin,
      boolean slaBreached,
      String causationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", "changed");
    data.put("regulation_request_id", r.id);
    data.put("status", r.status);
    data.put("previous_status", previousStatus == null ? "none" : previousStatus);
    data.put("priority", r.priority);
    OffsetDateTime at = occurredAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : occurredAt;
    data.put("occurred_at", at);
    data.put("actor_kind", actorKind);
    put(data, "actor_id", actorId);
    cnes(data, "provider_cnes", r.providerCnes);
    if (r.scheduledAt != null) {
      data.put("scheduled_at", utc(r.scheduledAt));
    }
    put(data, "appointment_id", r.appointmentId);
    if (returnToOrigin != null) {
      data.put("return_to_origin", returnToOrigin);
    }
    put(data, "reason", truncate(reason));
    if (slaBreached) {
      data.put("sla_breached", true);
    }
    return publisher.publish(
        new DomainEvent(
            STATUS_AGGREGATE,
            r.id,
            STATUS_PREFIX + "changed",
            EVENT_VERSION,
            at,
            new EventEnvelope.Subject(r.citizenId, null),
            source(source, r),
            data,
            DomainEvent.restricted(PURPOSES),
            causationId));
  }

  private static EventEnvelope.Source source(
      RegulationRequestRegistration.SourceRef source, RegulationRequest r) {
    if (source == null) {
      return new EventEnvelope.Source(
          r.sourceSystem, "core-municipal", r.sourceRecordId, null, null);
    }
    return new EventEnvelope.Source(
        source.system(),
        source.connector(),
        source.sourceRecordId(),
        source.sourceRecordVersion(),
        Cnes.isValid(source.cnes()) ? source.cnes().trim() : null);
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

  private static String truncate(String s) {
    return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
  }
}
