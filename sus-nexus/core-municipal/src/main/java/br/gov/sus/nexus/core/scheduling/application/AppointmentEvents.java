package br.gov.sus.nexus.core.scheduling.application;

import br.gov.sus.nexus.core.platform.events.DomainEvent;
import br.gov.sus.nexus.core.platform.events.EventEnvelope;
import br.gov.sus.nexus.core.platform.events.EventPublisher;
import br.gov.sus.nexus.core.platform.security.Purpose;
import br.gov.sus.nexus.core.scheduling.api.AppointmentRegistration;
import br.gov.sus.nexus.core.scheduling.domain.Appointment;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publica {@code sus.schedule.appointment.*} (tópico {@code sus.schedule.appointment.v1}) conforme
 * {@code schedule/appointment.v1.schema.json}. Subject carrega só o {@code municipal_citizen_id}.
 */
@ApplicationScoped
public class AppointmentEvents {

  public static final String EVENT_PREFIX = "sus.schedule.appointment.";
  public static final String AGGREGATE_TYPE = "appointment";
  static final String EVENT_VERSION = "1.0";

  @Inject EventPublisher publisher;

  public String publish(
      String action,
      Appointment a,
      String previousStatus,
      AppointmentRegistration.SourceRef source,
      OffsetDateTime occurredAt,
      String causationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("appointment_id", a.id);
    data.put("status", a.status);
    data.put("kind", a.kind);
    put(data, "service_code", a.serviceCode);
    put(data, "code_system", a.codeSystem);
    if (Cnes.isValid(a.healthUnitCnes)) {
      data.put("health_unit_cnes", a.healthUnitCnes);
    }
    put(data, "professional_id", a.professionalId);
    data.put("scheduled_start", a.scheduledStart.atOffset(ZoneOffset.UTC));
    if (a.scheduledEnd != null) {
      data.put("scheduled_end", a.scheduledEnd.atOffset(ZoneOffset.UTC));
    }
    put(data, "regulation_request_id", a.regulationRequestId);
    put(data, "exam_order_id", a.examOrderId);
    put(data, "care_line", a.careLine);
    put(data, "cancellation_reason", a.cancellationReason);
    put(data, "previous_status", previousStatus);

    EventEnvelope.Source src =
        source == null
            ? new EventEnvelope.Source(
                a.sourceSystem, "core-municipal", a.sourceRecordId, null, null)
            : new EventEnvelope.Source(
                source.system(),
                source.connector(),
                source.sourceRecordId(),
                source.sourceRecordVersion(),
                Cnes.isValid(source.cnes()) ? source.cnes().trim() : null);
    return publisher.publish(
        new DomainEvent(
            AGGREGATE_TYPE,
            a.id,
            EVENT_PREFIX + action,
            EVENT_VERSION,
            occurredAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : occurredAt,
            new EventEnvelope.Subject(a.citizenId, null),
            src,
            data,
            new EventEnvelope.Privacy(
                "internal", List.of(Purpose.SCHEDULING.wire(), Purpose.CARE_COORDINATION.wire())),
            causationId));
  }

  private static void put(Map<String, Object> m, String k, String v) {
    if (v != null && !v.isBlank()) {
      m.put(k, v);
    }
  }
}
