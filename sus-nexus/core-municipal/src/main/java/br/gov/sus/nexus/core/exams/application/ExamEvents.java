package br.gov.sus.nexus.core.exams.application;

import br.gov.sus.nexus.core.exams.api.ExamOrderRegistration;
import br.gov.sus.nexus.core.exams.domain.ExamOrder;
import br.gov.sus.nexus.core.exams.domain.ExamResult;
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
 * Publica {@code sus.exam.order.*} ({@code exam/order.v1.schema.json}, classificação {@code
 * internal}) e {@code sus.exam.result.*} ({@code exam/result.v1.schema.json}, {@code restricted}):
 * só metadados; o laudo vai por {@code data_ref} (KAF-009).
 */
@ApplicationScoped
public class ExamEvents {

  public static final String ORDER_PREFIX = "sus.exam.order.";
  public static final String RESULT_PREFIX = "sus.exam.result.";
  public static final String ORDER_AGGREGATE = "exam_order";
  public static final String RESULT_AGGREGATE = "exam_result";
  static final String EVENT_VERSION = "1.0";
  static final List<String> PURPOSES = List.of(Purpose.CARE_COORDINATION.wire());

  @Inject EventPublisher publisher;

  public String publishOrder(
      String action,
      ExamOrder o,
      String previousStatus,
      ExamOrderRegistration.SourceRef source,
      OffsetDateTime occurredAt,
      String reason,
      String causationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("exam_order_id", o.id);
    data.put("status", o.status);
    put(data, "previous_status", previousStatus);
    data.put("exam_code", o.examCode);
    data.put("code_system", o.codeSystem);
    put(data, "category", o.category);
    put(data, "priority", o.priority);
    data.put("requested_at", utc(o.requestedAt));
    cnes(data, "requesting_cnes", o.requestingCnes);
    cnes(data, "performer_cnes", o.performerCnes);
    put(data, "regulation_request_id", o.regulationRequestId);
    put(data, "appointment_id", o.appointmentId);
    if (o.scheduledAt != null) {
      data.put("scheduled_at", utc(o.scheduledAt));
    }
    put(data, "care_line", o.careLine);
    put(data, "reason", truncate(reason));
    return publisher.publish(
        new DomainEvent(
            ORDER_AGGREGATE,
            o.id,
            ORDER_PREFIX + action,
            EVENT_VERSION,
            occurredAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : occurredAt,
            new EventEnvelope.Subject(o.citizenId, null),
            source(source, o),
            data,
            new EventEnvelope.Privacy("internal", PURPOSES),
            causationId));
  }

  public String publishResult(
      String action,
      ExamOrder o,
      ExamResult r,
      ExamOrderRegistration.SourceRef source,
      String causationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("exam_order_id", o.id);
    data.put("exam_result_id", r.id);
    data.put("result_status", r.status);
    data.put("critical", r.critical);
    data.put("reported_at", utc(r.reportedAt));
    cnes(data, "performer_cnes", r.performerCnes);
    data.put("has_document", r.documentRef != null);
    data.put("observations_count", r.observationsCount);
    cnes(data, "requesting_cnes", o.requestingCnes);
    put(data, "care_line", o.careLine);
    return publisher.publish(
        new DomainEvent(
            RESULT_AGGREGATE,
            o.id,
            RESULT_PREFIX + action,
            EVENT_VERSION,
            utc(r.reportedAt),
            new EventEnvelope.Subject(o.citizenId, null),
            source(source, o),
            data,
            DomainEvent.restricted(PURPOSES),
            causationId,
            r.documentRef));
  }

  private static EventEnvelope.Source source(ExamOrderRegistration.SourceRef source, ExamOrder o) {
    if (source == null) {
      return new EventEnvelope.Source(
          o.sourceSystem, "core-municipal", o.sourceRecordId, null, null);
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
