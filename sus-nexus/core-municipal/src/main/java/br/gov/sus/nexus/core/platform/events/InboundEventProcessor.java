package br.gov.sus.nexus.core.platform.events;

import br.gov.sus.nexus.core.platform.correlation.CorrelationId;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.function.Function;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Suporte comum aos consumidores Kafka (padrão KAF-0xx): desserializa o envelope, ativa o contexto
 * do tenant do envelope ({@link TenantTransactions#runAs}), propaga o {@code correlation_id} e
 * executa o handler em transação NOVA junto com o {@link EventInbox} (exactly-once por grupo).
 *
 * <p>Erros de desserialização/contrato são registrados e descartados (mensagem venenosa — vai para
 * DLQ pela política de retry do conector Kafka); erros de negócio propagam para o retry.
 */
@ApplicationScoped
public class InboundEventProcessor {

  private static final Logger LOG = Logger.getLogger(InboundEventProcessor.class);

  /** Evento recebido: envelope tipado + nó JSON bruto (para o {@code data}). */
  public record Inbound(EventEnvelope envelope, JsonNode raw) {
    public JsonNode data() {
      return raw.get("data");
    }

    public String eventId() {
      return envelope.eventId();
    }

    public String eventType() {
      return envelope.eventType();
    }

    public String tenantId() {
      return envelope.tenant().municipalityId();
    }

    /** Última parte do {@code event_type} ({@code created}, {@code no_show}, ...). */
    public String action() {
      String t = envelope.eventType();
      return t.substring(t.lastIndexOf('.') + 1);
    }

    public String citizenId() {
      return envelope.subject() == null ? null : envelope.subject().municipalCitizenId();
    }

    public String causationId() {
      return envelope.trace() == null ? null : envelope.trace().causationId();
    }

    public String correlationId() {
      return envelope.trace() == null ? null : envelope.trace().correlationId();
    }
  }

  @Inject ObjectMapper objectMapper;
  @Inject TenantTransactions transactions;
  @Inject EventInbox inbox;

  public Optional<Inbound> parse(String payload) {
    try {
      JsonNode raw = objectMapper.readTree(payload);
      EventEnvelope envelope = objectMapper.treeToValue(raw, EventEnvelope.class);
      if (envelope.eventId() == null
          || envelope.eventType() == null
          || envelope.tenant() == null
          || envelope.tenant().municipalityId() == null) {
        LOG.warn("envelope inválido descartado (sem event_id/event_type/tenant)");
        return Optional.empty();
      }
      return Optional.of(new Inbound(envelope, raw));
    } catch (Exception e) {
      LOG.warnf("envelope não desserializável descartado: %s", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /**
   * Processa o evento uma única vez por {@code consumerGroup}: tenant do envelope aplicado, nova
   * transação, inbox marcado na mesma transação do handler. Retorna vazio se duplicado/descartado.
   */
  public <T> Optional<T> process(
      String payload, String consumerGroup, Function<Inbound, T> handler) {
    Optional<Inbound> parsed = parse(payload);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    Inbound in = parsed.get();
    String corr = in.correlationId();
    return transactions.runAs(
        in.tenantId(),
        () -> {
          CorrelationId correlationId = Arc.container().instance(CorrelationId.class).get();
          if (corr != null) {
            correlationId.set(corr);
          }
          MDC.put(CorrelationId.MDC_KEY, correlationId.get());
          MDC.put("tenant_id", in.tenantId());
          try {
            return QuarkusTransaction.requiringNew()
                .call(
                    () -> {
                      transactions.applyCurrentTenant();
                      Optional<T> result =
                          inbox.once(in.eventId(), consumerGroup, () -> handler.apply(in));
                      if (result.isEmpty()) {
                        LOG.debugf(
                            "evento %s já processado por %s (duplicata)",
                            in.eventId(), consumerGroup);
                      }
                      return result;
                    });
          } finally {
            MDC.remove(CorrelationId.MDC_KEY);
            MDC.remove("tenant_id");
          }
        });
  }
}
