package br.gov.sus.nexus.core.platform.events;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Evento de domínio a publicar via outbox. O {@link EventPublisher} completa o envelope com
 * event_id, tenant, published_at e trace (correlation id da requisição).
 *
 * @param aggregateType tipo do agregado (ex.: {@code citizen})
 * @param aggregateId id do agregado (chave de partição quando não há subject)
 * @param eventType {@code sus.<domínio>.<entidade>.<ação>}
 * @param eventVersion versão do payload (ex.: {@code 1.0})
 * @param occurredAt quando o fato ocorreu
 * @param subject cidadão (ids mascarados/hash apenas) — pode ser nulo
 * @param source origem do dado
 * @param data payload conforme schema do tópico
 * @param privacy classificação e finalidades
 * @param causationId evento/mensagem que causou este (opcional)
 * @param dataRef referência segura ao dado completo (ex.: laudo) que NUNCA trafega no evento
 */
public record DomainEvent(
    String aggregateType,
    String aggregateId,
    String eventType,
    String eventVersion,
    OffsetDateTime occurredAt,
    EventEnvelope.Subject subject,
    EventEnvelope.Source source,
    Map<String, Object> data,
    EventEnvelope.Privacy privacy,
    String causationId,
    String dataRef) {

  public DomainEvent(
      String aggregateType,
      String aggregateId,
      String eventType,
      String eventVersion,
      OffsetDateTime occurredAt,
      EventEnvelope.Subject subject,
      EventEnvelope.Source source,
      Map<String, Object> data,
      EventEnvelope.Privacy privacy,
      String causationId) {
    this(
        aggregateType,
        aggregateId,
        eventType,
        eventVersion,
        occurredAt,
        subject,
        source,
        data,
        privacy,
        causationId,
        null);
  }

  public static EventEnvelope.Privacy restricted(List<String> purposes) {
    return new EventEnvelope.Privacy("restricted", purposes);
  }
}
