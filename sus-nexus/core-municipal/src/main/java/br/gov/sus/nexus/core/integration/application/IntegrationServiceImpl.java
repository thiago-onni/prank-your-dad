package br.gov.sus.nexus.core.integration.application;

import br.gov.sus.nexus.core.audit.api.AuditEntry;
import br.gov.sus.nexus.core.audit.api.AuditService;
import br.gov.sus.nexus.core.integration.api.ConnectorHeartbeat;
import br.gov.sus.nexus.core.integration.api.ConnectorStatusDto;
import br.gov.sus.nexus.core.integration.api.DeadLetterDto;
import br.gov.sus.nexus.core.integration.api.ErrorDetail;
import br.gov.sus.nexus.core.integration.api.IntegrationMessageDto;
import br.gov.sus.nexus.core.integration.api.IntegrationMessageStatus;
import br.gov.sus.nexus.core.integration.api.IntegrationMessageWrite;
import br.gov.sus.nexus.core.integration.api.IntegrationService;
import br.gov.sus.nexus.core.integration.api.ReconciliationEntryDto;
import br.gov.sus.nexus.core.integration.domain.Connector;
import br.gov.sus.nexus.core.integration.domain.DeadLetter;
import br.gov.sus.nexus.core.integration.domain.IntegrationError;
import br.gov.sus.nexus.core.integration.domain.IntegrationMessage;
import br.gov.sus.nexus.core.integration.domain.Reconciliation;
import br.gov.sus.nexus.core.integration.infrastructure.IntegrationRepositories;
import br.gov.sus.nexus.core.platform.errors.ConflictException;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.events.DomainEvent;
import br.gov.sus.nexus.core.platform.events.EventEnvelope;
import br.gov.sus.nexus.core.platform.events.EventPublisher;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.security.Purpose;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registry de conectores, ledger espelho (sem payload), DLQ, reconciliação e reprocessamento. */
@ApplicationScoped
public class IntegrationServiceImpl implements IntegrationService {

  public static final String REPROCESS_EVENT_TYPE = "sus.integration.reprocess.requested";
  static final String EVENT_VERSION = "1.0";
  private static final Set<String> HEALTH = Set.of("healthy", "degraded", "down", "unknown");

  @Inject IntegrationRepositories.Connectors connectors;
  @Inject IntegrationRepositories.Messages messages;
  @Inject IntegrationRepositories.Errors errors;
  @Inject IntegrationRepositories.DeadLetters deadLetters;
  @Inject IntegrationRepositories.Reconciliations reconciliations;
  @Inject TenantContext tenantContext;
  @Inject CurrentActor currentActor;
  @Inject EventPublisher publisher;
  @Inject AuditService audit;

  @Override
  @TenantTransactional
  public List<ConnectorStatusDto> listConnectors() {
    return connectors.all().stream().map(this::status).toList();
  }

  @Override
  @TenantTransactional
  public ConnectorStatusDto heartbeat(String connectorId, ConnectorHeartbeat hb) {
    Connector c = connectorOrNew(connectorId, hb.sourceSystem(), hb.connectorVersion());
    c.connectorVersion = hb.connectorVersion();
    c.sourceSystem = hb.sourceSystem();
    if (hb.health() != null) {
      if (!HEALTH.contains(hb.health())) {
        throw DomainValidationException.field("health", "valores: healthy|degraded|down|unknown");
      }
      c.health = hb.health();
    }
    c.healthDetail = hb.detail();
    if (hb.descriptor() != null) {
      c.descriptor = new LinkedHashMap<>(hb.descriptor());
    }
    if (hb.metrics() != null) {
      Map<String, Object> merged = new LinkedHashMap<>(c.metrics);
      merged.putAll(hb.metrics());
      c.metrics = merged;
    }
    c.lastHeartbeatAt = Instant.now();
    c.updatedAt = Instant.now();
    return status(c);
  }

  @Override
  @TenantTransactional
  public IntegrationMessageDto recordMessage(IntegrationMessageWrite w) {
    if (!w.id().startsWith("msg_")) {
      throw DomainValidationException.field("id", "esperado msg_<ULID>");
    }
    String tenant = tenantContext.require();
    Instant now = Instant.now();
    IntegrationMessage m = messages.findById(w.id());
    boolean created = m == null;
    if (created) {
      m = new IntegrationMessage();
      m.id = w.id();
      m.tenantId = tenant;
      m.receivedAt = w.receivedAt() == null ? now : w.receivedAt().toInstant();
      m.createdAt = now;
    }
    m.connectorId = w.connectorId();
    m.sourceSystem = w.sourceSystem();
    m.sourceRecordId = w.sourceRecordId();
    m.sourceRecordVersion = w.sourceRecordVersion();
    m.entityType = w.entityType();
    m.status = w.status().wire();
    m.rawRef = w.rawRef();
    m.rawSha256 = w.rawSha256();
    m.correlationId = w.correlationId();
    if (created) {
      messages.persist(m); // antes dos filhos (error/dead_letter referenciam a mensagem)
    }
    if (w.processedAt() != null) {
      m.processedAt = w.processedAt().toInstant();
    }
    if (w.attempts() != null) {
      m.attempts = w.attempts();
    }
    if (w.lastError() != null) {
      m.lastError = errorMap(w.lastError());
      IntegrationError e = new IntegrationError();
      e.id = Ulid.generate(Ulid.INTEGRATION_ERROR);
      e.tenantId = tenant;
      e.messageId = m.id;
      e.connectorId = m.connectorId;
      e.stage = w.lastError().stage();
      e.code = w.lastError().code();
      e.message = w.lastError().message();
      e.attempt = m.attempts;
      e.occurredAt =
          w.lastError().occurredAt() == null ? now : w.lastError().occurredAt().toInstant();
      errors.persist(e);
    }
    m.updatedAt = now;
    if (w.deadLetter() != null) {
      DeadLetter dl =
          deadLetters
              .findOpenByMessage(m.id)
              .orElseGet(
                  () -> {
                    DeadLetter n = new DeadLetter();
                    n.id = Ulid.generate(Ulid.DEAD_LETTER);
                    n.tenantId = tenantContext.require();
                    n.messageId = w.id();
                    return n;
                  });
      dl.connectorId = m.connectorId;
      dl.topic = w.deadLetter().topic();
      dl.reason = w.deadLetter().reason();
      dl.stage = w.deadLetter().stage();
      dl.owner = w.deadLetter().owner();
      dl.payloadRef = w.deadLetter().payloadRef();
      dl.attempts = m.attempts;
      deadLetters.persist(dl);
    }
    Connector c = connectorOrNew(m.connectorId, m.sourceSystem, "unknown");
    if (c.lastMessageAt == null || c.lastMessageAt.isBefore(m.receivedAt)) {
      c.lastMessageAt = m.receivedAt;
    }
    c.updatedAt = now;
    return toDto(m);
  }

  @Override
  @TenantTransactional
  public Page<IntegrationMessageDto> listMessages(
      String connectorId,
      IntegrationMessageStatus status,
      OffsetDateTime from,
      OffsetDateTime to,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    String before = Cursor.decode(cursor).orElse(null);
    List<IntegrationMessageDto> rows =
        messages
            .list(
                blankToNull(connectorId),
                status == null ? null : status.wire(),
                from == null ? null : from.toInstant(),
                to == null ? null : to.toInstant(),
                before,
                size + 1)
            .stream()
            .map(IntegrationServiceImpl::toDto)
            .toList();
    return Page.of(rows, size, IntegrationMessageDto::id);
  }

  @Override
  @TenantTransactional
  public IntegrationMessageDto getMessage(String messageId) {
    return toDto(load(messageId));
  }

  @Override
  @TenantTransactional
  public String reprocess(String messageId, String reason) {
    IntegrationMessage m = load(messageId);
    IntegrationMessageStatus st = IntegrationMessageStatus.fromWire(m.status);
    if (st != IntegrationMessageStatus.FAILED
        && st != IntegrationMessageStatus.DEAD_LETTERED
        && st != IntegrationMessageStatus.PROCESSED
        && st != IntegrationMessageStatus.PUBLISHED) {
      throw new ConflictException("mensagem não reprocessável no status " + m.status);
    }
    m.status = IntegrationMessageStatus.REPROCESSING.wire();
    m.updatedAt = Instant.now();
    deadLetters
        .findOpenByMessage(m.id)
        .ifPresent(
            dl -> {
              dl.triagedAt = Instant.now();
              dl.triagedBy = currentActor.actorId();
            });
    audit.record(
        AuditEntry.of("integration_message.reprocess_requested", "integration_message", m.id, null)
            .withReason(reason)
            .withDetails(Map.of("connector_id", m.connectorId, "previous_status", st.wire())));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("action", "requested");
    data.put("message_id", m.id);
    data.put("connector_id", m.connectorId);
    data.put("source_system", m.sourceSystem);
    if (m.sourceRecordId != null) {
      data.put("source_record_id", m.sourceRecordId);
    }
    if (m.rawRef != null) {
      data.put("raw_ref", m.rawRef);
    }
    data.put("requested_by", currentActor.actorId());
    if (reason != null) {
      data.put("reason", reason.length() > 500 ? reason.substring(0, 500) : reason);
    }
    data.put("suppress_external_effects", true);
    return publisher.publish(
        new DomainEvent(
            "integration_message",
            m.id,
            REPROCESS_EVENT_TYPE,
            EVENT_VERSION,
            OffsetDateTime.now(ZoneOffset.UTC),
            null,
            new EventEnvelope.Source(
                m.sourceSystem,
                "core-municipal",
                m.sourceRecordId == null ? m.id : m.sourceRecordId,
                m.sourceRecordVersion,
                null),
            data,
            new EventEnvelope.Privacy("internal", List.of(Purpose.INTEGRATION_OPERATIONS.wire())),
            null));
  }

  @Override
  @TenantTransactional
  public Page<DeadLetterDto> listDeadLetters(String connectorId, String cursor, Integer limit) {
    int size = Cursor.limit(limit);
    List<DeadLetterDto> rows =
        deadLetters
            .list(blankToNull(connectorId), Cursor.decode(cursor).orElse(null), size + 1)
            .stream()
            .map(IntegrationServiceImpl::toDto)
            .toList();
    return Page.of(rows, size, DeadLetterDto::id);
  }

  @Override
  @TenantTransactional
  public ReconciliationEntryDto recordReconciliation(ReconciliationEntryDto e) {
    Reconciliation r = new Reconciliation();
    r.id = Ulid.generate(Ulid.RECONCILIATION);
    r.tenantId = tenantContext.require();
    r.connectorId = e.connectorId();
    r.entityType = e.entityType();
    r.periodStart = e.periodStart().toInstant();
    r.periodEnd = e.periodEnd().toInstant();
    r.sourceCount = e.sourceCount();
    r.busCount = e.busCount();
    r.gap = e.gap();
    r.checkedAt = e.checkedAt() == null ? Instant.now() : e.checkedAt().toInstant();
    reconciliations.persist(r);
    connectorOrNew(e.connectorId(), "unknown", "unknown").updatedAt = Instant.now();
    return toDto(r);
  }

  @Override
  @TenantTransactional
  public Page<ReconciliationEntryDto> listReconciliation(
      String connectorId, String cursor, Integer limit) {
    int size = Cursor.limit(limit);
    List<ReconciliationEntryDto> rows =
        reconciliations
            .list(blankToNull(connectorId), Cursor.decode(cursor).orElse(null), size + 1)
            .stream()
            .map(IntegrationServiceImpl::toDto)
            .toList();
    return Page.of(rows, size, ReconciliationEntryDto::id);
  }

  @Override
  @TenantTransactional
  @SuppressWarnings("unchecked")
  public void applyStatusEvent(String action, Map<String, Object> data, OffsetDateTime occurredAt) {
    String connectorId = String.valueOf(data.get("connector_id"));
    String version = String.valueOf(data.getOrDefault("connector_version", "unknown"));
    String sourceSystem = String.valueOf(data.getOrDefault("source_system", "unknown"));
    Connector c = connectorOrNew(connectorId, sourceSystem, version);
    c.connectorVersion = version;
    if (!"unknown".equals(sourceSystem)) {
      c.sourceSystem = sourceSystem;
    }
    Instant at = occurredAt == null ? Instant.now() : occurredAt.toInstant();
    Object metrics = data.get("metrics");
    if (metrics instanceof Map<?, ?> mm) {
      Map<String, Object> merged = new LinkedHashMap<>(c.metrics);
      merged.putAll((Map<String, Object>) mm);
      c.metrics = merged;
    }
    switch (action) {
      case "healthy", "degraded", "down" -> {
        c.health = action;
        c.healthDetail = data.get("detail") == null ? null : String.valueOf(data.get("detail"));
        c.lastHeartbeatAt = at;
      }
      case "reconciliation_gap" -> {
        Reconciliation r = new Reconciliation();
        r.id = Ulid.generate(Ulid.RECONCILIATION);
        r.tenantId = tenantContext.require();
        r.connectorId = connectorId;
        r.entityType =
            data.get("entity_type") == null ? "all" : String.valueOf(data.get("entity_type"));
        Object period = data.get("period");
        Instant start = at.minus(Duration.ofDays(1));
        Instant end = at;
        if (period instanceof Map<?, ?> p) {
          if (p.get("start") != null) {
            start = OffsetDateTime.parse(String.valueOf(p.get("start"))).toInstant();
          }
          if (p.get("end") != null) {
            end = OffsetDateTime.parse(String.valueOf(p.get("end"))).toInstant();
          }
        }
        r.periodStart = start;
        r.periodEnd = end;
        int gap = 0;
        int received = 0;
        int processed = 0;
        if (metrics instanceof Map<?, ?> mm) {
          gap = intOf(mm.get("reconciliation_gap"));
          received = intOf(mm.get("received_total"));
          processed = intOf(mm.get("processed_total"));
        }
        r.gap = gap;
        r.sourceCount = received;
        r.busCount = processed;
        r.checkedAt = at;
        r.details = Map.of("detail", String.valueOf(data.getOrDefault("detail", "")));
        reconciliations.persist(r);
      }
      default -> {
        // ação desconhecida: ignorada (compatibilidade para frente)
      }
    }
    c.updatedAt = Instant.now();
  }

  // ---------------------------------------------------------------------

  private Connector connectorOrNew(String connectorId, String sourceSystem, String version) {
    String tenant = tenantContext.require();
    return connectors
        .find(tenant, connectorId)
        .orElseGet(
            () -> {
              Connector n = new Connector();
              n.tenantId = tenant;
              n.connectorId = connectorId;
              n.sourceSystem = sourceSystem == null ? "unknown" : sourceSystem;
              n.connectorVersion = version == null ? "unknown" : version;
              connectors.persist(n);
              return n;
            });
  }

  private ConnectorStatusDto status(Connector c) {
    Instant since = Instant.now().minus(Duration.ofHours(24));
    int gap = reconciliations.latest(c.connectorId).map(r -> r.gap).orElse(0);
    return new ConnectorStatusDto(
        c.connectorId,
        c.connectorVersion,
        c.sourceSystem,
        c.health,
        c.healthDetail,
        offset(c.lastMessageAt),
        offset(c.lastHeartbeatAt),
        messages.countSince(c.connectorId, since),
        messages.countFailedSince(c.connectorId, since),
        deadLetters.countOpen(c.connectorId),
        gap);
  }

  private IntegrationMessage load(String id) {
    IntegrationMessage m = messages.findById(id);
    if (m == null) {
      throw new NotFoundException("mensagem de integração", id);
    }
    return m;
  }

  static IntegrationMessageDto toDto(IntegrationMessage m) {
    ErrorDetail err = null;
    if (m.lastError != null) {
      Object at = m.lastError.get("occurred_at");
      err =
          new ErrorDetail(
              str(m.lastError.get("code")),
              str(m.lastError.get("message")),
              str(m.lastError.get("stage")),
              at == null ? null : OffsetDateTime.parse(at.toString()));
    }
    return new IntegrationMessageDto(
        m.id,
        m.connectorId,
        m.sourceSystem,
        m.sourceRecordId,
        m.sourceRecordVersion,
        m.entityType,
        IntegrationMessageStatus.fromWire(m.status),
        m.rawRef,
        m.rawSha256,
        m.correlationId,
        offset(m.receivedAt),
        offset(m.processedAt),
        m.attempts,
        err);
  }

  static DeadLetterDto toDto(DeadLetter d) {
    return new DeadLetterDto(
        d.id,
        d.messageId,
        d.connectorId,
        d.topic,
        d.reason,
        d.stage,
        d.attempts,
        d.owner,
        d.payloadRef,
        offset(d.createdAt),
        offset(d.triagedAt));
  }

  static ReconciliationEntryDto toDto(Reconciliation r) {
    return new ReconciliationEntryDto(
        r.id,
        r.connectorId,
        r.entityType,
        offset(r.periodStart),
        offset(r.periodEnd),
        r.sourceCount,
        r.busCount,
        r.gap,
        offset(r.checkedAt));
  }

  static Map<String, Object> errorMap(ErrorDetail e) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (e.code() != null) {
      m.put("code", e.code());
    }
    if (e.message() != null) {
      m.put("message", e.message().length() > 1000 ? e.message().substring(0, 1000) : e.message());
    }
    if (e.stage() != null) {
      m.put("stage", e.stage());
    }
    m.put(
        "occurred_at",
        (e.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : e.occurredAt()).toString());
    return m;
  }

  private static OffsetDateTime offset(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static int intOf(Object o) {
    if (o instanceof Number n) {
      return n.intValue();
    }
    try {
      return o == null ? 0 : Integer.parseInt(o.toString());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
