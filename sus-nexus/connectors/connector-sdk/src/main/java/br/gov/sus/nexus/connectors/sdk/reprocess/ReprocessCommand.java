package br.gov.sus.nexus.connectors.sdk.reprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;

/**
 * Comando de reprocessamento publicado pelo core em {@value #TOPIC} ({@code event_type} {@value
 * #EVENT_TYPE}; data = {@code contracts/events/integration/reprocess.v1.schema.json}). Só ids e
 * referências: nunca o payload (KAF-012).
 *
 * @param eventId {@code event_id} do envelope (deduplicação do comando)
 * @param tenantId {@code tenant.municipality_id} do envelope
 * @param messageId {@code integration_message} a reprocessar ({@code msg_<ULID>})
 * @param rawRef referência à raw zone (fallback quando o ledger local não tem a mensagem)
 */
public record ReprocessCommand(
    String eventId,
    String eventType,
    String tenantId,
    String correlationId,
    String messageId,
    String connectorId,
    String sourceSystem,
    String sourceRecordId,
    String rawRef,
    String requestedBy,
    String reason,
    boolean suppressExternalEffects) {

  public static final String TOPIC = "sus.integration.command.v1";
  public static final String EVENT_TYPE = "sus.integration.reprocess.requested";

  /** Lê o envelope; vazio se ilegível ou sem {@code event_id}/{@code data}. */
  public static Optional<ReprocessCommand> parse(ObjectMapper mapper, String envelopeJson) {
    JsonNode env;
    try {
      env = mapper.readTree(envelopeJson);
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
    if (env == null || !env.isObject()) return Optional.empty();
    JsonNode data = env.path("data");
    String eventId = text(env, "event_id");
    if (eventId == null || !data.isObject()) return Optional.empty();
    return Optional.of(
        new ReprocessCommand(
            eventId,
            text(env, "event_type"),
            text(env.path("tenant"), "municipality_id"),
            text(env.path("trace"), "correlation_id"),
            text(data, "message_id"),
            text(data, "connector_id"),
            text(data, "source_system"),
            text(data, "source_record_id"),
            text(data, "raw_ref"),
            text(data, "requested_by"),
            text(data, "reason"),
            data.path("suppress_external_effects").asBoolean(false)));
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
  }
}
