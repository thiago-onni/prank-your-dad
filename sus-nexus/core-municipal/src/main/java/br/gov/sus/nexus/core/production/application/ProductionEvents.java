package br.gov.sus.nexus.core.production.application;

import br.gov.sus.nexus.core.platform.events.DomainEvent;
import br.gov.sus.nexus.core.platform.events.EventEnvelope;
import br.gov.sus.nexus.core.platform.events.EventPublisher;
import br.gov.sus.nexus.core.platform.security.Purpose;
import br.gov.sus.nexus.core.production.domain.ProductionBatch;
import br.gov.sus.nexus.core.production.domain.ProductionOutcome;
import br.gov.sus.nexus.core.production.domain.ProductionRecord;
import br.gov.sus.nexus.core.production.domain.ProductionValidationIssue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publica {@code sus.production.{record,validation,submission,outcome}.*} via outbox ({@code
 * contracts/events/production/*.v1.schema.json}). Payload mínimo: sem nomes; CNS/CPF só por hash.
 * Sem {@code subject}: a chave de partição é o id do registro/lote (topics.yaml) e a produção não é
 * projetada na timeline do cidadão (dado administrativo de faturamento).
 */
@ApplicationScoped
public class ProductionEvents {

  public static final String RECORD_AGGREGATE = "production_record";
  public static final String ISSUE_AGGREGATE = "production_issue";
  public static final String BATCH_AGGREGATE = "production_batch";
  public static final String OUTCOME_AGGREGATE = "production_outcome";
  static final String EVENT_VERSION = "1.0";
  static final EventEnvelope.Privacy PRIVACY =
      new EventEnvelope.Privacy("restricted", List.of(Purpose.PRODUCTION_AUDIT.wire()));

  @Inject EventPublisher publisher;

  public String publishRecord(
      String action, ProductionRecord r, int errors, int warnings, String causationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("production_record_id", r.id);
    data.put("kind", r.kind);
    data.put("competence", r.competence);
    data.put("cnes", r.cnes);
    data.put("procedure_code", r.procedureCode);
    data.put("quantity", r.quantity);
    data.put("professional_cbo", r.professionalCbo);
    put(data, "professional_cns_hash", r.professionalCnsHash);
    put(data, "municipal_citizen_id", r.citizenId);
    put(data, "citizen_identifier_hash", r.citizenIdentifierHash);
    data.put("attendance_date", r.attendanceDate.toString());
    data.put("status", r.status);
    data.put("errors_count", errors);
    data.put("warnings_count", warnings);
    put(data, "rule_version", r.ruleVersion);
    if (r.estimatedValue != null) {
      data.put("estimated_value", r.estimatedValue);
    }
    if (r.deadlineAt != null) {
      data.put("deadline_at", utc(r.deadlineAt).toString());
    }
    data.put("correction_count", r.correctionCount);
    return publisher.publish(
        new DomainEvent(
            RECORD_AGGREGATE,
            r.id,
            "sus.production.record." + action,
            EVENT_VERSION,
            utc(Instant.now()),
            null,
            source(r),
            data,
            PRIVACY,
            causationId));
  }

  public String publishIssue(String action, ProductionValidationIssue i, ProductionRecord r) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("production_record_id", r.id);
    data.put("issue_id", i.id);
    data.put("rule_id", i.ruleId);
    data.put("rule_version", i.ruleVersion);
    data.put("severity", i.severity);
    put(data, "field", i.field);
    data.put("origin", i.origin);
    data.put("status", i.status);
    data.put("kind", r.kind);
    data.put("competence", r.competence);
    data.put("cnes", r.cnes);
    put(data, "task_id", i.taskId);
    return publisher.publish(
        new DomainEvent(
            ISSUE_AGGREGATE,
            r.id,
            "sus.production.validation." + action,
            EVENT_VERSION,
            utc(i.resolvedAt != null ? i.resolvedAt : i.createdAt),
            null,
            source(r),
            data,
            PRIVACY,
            null));
  }

  public String publishBatch(
      String action, ProductionBatch b, String actorKind, String dataRef, String causationId) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", action);
    data.put("batch_id", b.id);
    data.put("competence", b.competence);
    data.put("cnes", b.cnes);
    data.put("kind", b.kind);
    data.put("status", b.status);
    data.put("records_count", b.recordsCount);
    data.put("total_quantity", b.totalQuantity);
    data.put("estimated_value", b.estimatedValue == null ? BigDecimal.ZERO : b.estimatedValue);
    put(data, "approved_by", b.approvedBy);
    put(data, "actor_kind", actorKind);
    if ("exported".equals(action)) {
      put(data, "layout", b.exportLayout);
      put(data, "file_sha256", b.exportSha256);
      if (b.exportLines != null) {
        data.put("file_lines", b.exportLines);
      }
    }
    put(data, "protocol_number", b.protocolNumber);
    return publisher.publish(
        new DomainEvent(
            BATCH_AGGREGATE,
            b.id,
            "sus.production.submission." + action,
            EVENT_VERSION,
            utc(Instant.now()),
            null,
            new EventEnvelope.Source("core-municipal", "core-municipal", b.id, null, b.cnes),
            data,
            PRIVACY,
            causationId,
            dataRef));
  }

  public String publishOutcome(ProductionOutcome o, ProductionRecord r, String sourceSystem) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", o.outcome);
    data.put("production_record_id", r.id);
    data.put("outcome_id", o.id);
    put(data, "batch_id", r.batchId);
    data.put("outcome", o.outcome);
    data.put("record_status", r.status);
    data.put("competence", r.competence);
    data.put("cnes", r.cnes);
    data.put("kind", r.kind);
    data.put("procedure_code", r.procedureCode);
    put(data, "reason_code", o.reasonCode);
    put(data, "reason", truncate(o.reason));
    if (o.paidAmount != null) {
      data.put("paid_amount", o.paidAmount);
    }
    if (o.approvedQuantity != null) {
      data.put("approved_quantity", o.approvedQuantity);
    }
    data.put("processed_at", utc(o.processedAt).toString());
    return publisher.publish(
        new DomainEvent(
            OUTCOME_AGGREGATE,
            r.id,
            "sus.production.outcome." + o.outcome,
            EVENT_VERSION,
            utc(o.processedAt),
            null,
            new EventEnvelope.Source(
                sourceSystem, "core-municipal", o.sourceRecordId, null, r.cnes),
            data,
            PRIVACY,
            null));
  }

  private static EventEnvelope.Source source(ProductionRecord r) {
    return new EventEnvelope.Source(
        r.sourceSystem, "core-municipal", r.sourceRecordId, r.sourceRecordVersion, r.cnes);
  }

  private static OffsetDateTime utc(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
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
