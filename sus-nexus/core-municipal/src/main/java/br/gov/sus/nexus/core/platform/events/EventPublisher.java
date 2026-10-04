package br.gov.sus.nexus.core.platform.events;

import br.gov.sus.nexus.core.platform.correlation.CorrelationId;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Publica eventos gravando no {@code platform.event_outbox} na MESMA transação da escrita de
 * domínio (Debezium Outbox Event Router faz o relay para Kafka). Deve ser chamado dentro de uma
 * transação ativa ({@link br.gov.sus.nexus.core.platform.tenant.TenantTransactional}).
 */
@ApplicationScoped
public class EventPublisher {

  private static final Logger LOG = Logger.getLogger(EventPublisher.class);
  static final String SCHEMA_VERSION = "1.0.0";

  private static final String INSERT_SQL =
      "insert into platform.event_outbox (id, tenant_id, aggregate_type, aggregate_id, event_type,"
          + " payload, headers, created_at) values (?1, ?2, ?3, ?4, ?5, cast(?6 as jsonb),"
          + " cast(?7 as jsonb), now())";

  @Inject EntityManager entityManager;
  @Inject ObjectMapper objectMapper;
  @Inject TenantContext tenantContext;
  @Inject CorrelationId correlationId;

  /** Grava o evento no outbox e retorna o {@code event_id} gerado. */
  public String publish(DomainEvent event) {
    String tenant = tenantContext.require();
    String eventId = Ulid.generate(Ulid.EVENT);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String corr = correlationId.get();

    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            event.eventType(),
            event.eventVersion(),
            event.occurredAt() != null ? event.occurredAt() : now,
            now,
            new EventEnvelope.Tenant(tenant, null),
            event.subject(),
            event.source(),
            event.data(),
            event.dataRef(),
            event.privacy(),
            new EventEnvelope.Trace(corr, event.causationId(), SCHEMA_VERSION),
            Boolean.FALSE);

    Map<String, Object> headers = new LinkedHashMap<>();
    headers.put("ce_id", eventId);
    headers.put("ce_type", event.eventType());
    headers.put(
        "ce_source", event.source() != null ? event.source().connector() : "core-municipal");
    headers.put("tenant_id", tenant);
    headers.put("correlation_id", corr);
    if (event.causationId() != null) {
      headers.put("causation_id", event.causationId());
    }
    headers.put("schema_version", SCHEMA_VERSION);
    headers.put("replay", false);

    try {
      String payload = objectMapper.writeValueAsString(envelope);
      String headersJson = objectMapper.writeValueAsString(headers);
      entityManager
          .createNativeQuery(INSERT_SQL)
          .setParameter(1, eventId)
          .setParameter(2, tenant)
          .setParameter(3, event.aggregateType())
          .setParameter(4, event.aggregateId())
          .setParameter(5, event.eventType())
          .setParameter(6, payload)
          .setParameter(7, headersJson)
          .executeUpdate();
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("falha ao serializar envelope de evento", e);
    }
    LOG.debugf("outbox <- %s %s (%s)", event.eventType(), eventId, event.aggregateId());
    return eventId;
  }
}
