package br.gov.sus.nexus.connectors.sdk.events;

import com.github.f4b6a3.ulid.Ulid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Envelopes ({@code contracts/events/envelope.schema.json}) para conectores que publicam em tópicos
 * de ingestão ({@code sus.ingest.*}) consumidos pelo core ({@code InboundEventProcessor}: {@code
 * event_id}, {@code event_type}, {@code tenant.municipality_id} obrigatórios; idempotência por
 * {@code event_inbox(event_id)}).
 *
 * <p>{@link #eventId(String...)} gera um {@code evt_<ULID>} <b>determinístico</b> (SHA-256 da
 * semente): o mesmo registro de origem (ex.: arquivo + linha) produz sempre o mesmo {@code
 * event_id}, de modo que reenvios e reprocessamentos são descartados pelo inbox do core. Os 48 bits
 * de "tempo" desse ULID não representam instante algum (use {@code occurred_at}).
 */
public final class IngestEnvelopes {

  public static final String EVENT_VERSION = "1.0";
  public static final String SCHEMA_VERSION = "1.0.0";

  /** Cabeçalhos Kafka (CONVENTIONS.md). */
  public static final List<String> HEADERS =
      List.of(
          "ce_id",
          "ce_type",
          "ce_source",
          "tenant_id",
          "correlation_id",
          "causation_id",
          "schema_version",
          "replay");

  private IngestEnvelopes() {}

  /** {@code evt_<ULID>} determinístico a partir das partes da semente. */
  public static String eventId(String... seed) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(String.join("\u001f", seed).getBytes(StandardCharsets.UTF_8));
      return "evt_" + Ulid.from(Arrays.copyOf(hash, 16)).toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 indisponível", e);
    }
  }

  /** Dados do envelope (todos obrigatórios exceto {@code causationId} e {@code occurredAt}). */
  public record Spec(
      String eventId,
      String eventType,
      String tenantId,
      Map<String, Object> source,
      Map<String, Object> data,
      String classification,
      List<String> purpose,
      String correlationId,
      String causationId,
      OffsetDateTime occurredAt,
      boolean replay) {}

  /** Monta o envelope como mapa (serializar com Jackson). */
  public static Map<String, Object> envelope(Spec s) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("event_id", s.eventId());
    env.put("event_type", s.eventType());
    env.put("event_version", EVENT_VERSION);
    env.put("occurred_at", (s.occurredAt() == null ? now : s.occurredAt()).toString());
    env.put("published_at", now.toString());
    env.put("tenant", Map.of("municipality_id", s.tenantId()));
    env.put("source", s.source());
    env.put("data", s.data());
    env.put("privacy", Map.of("classification", s.classification(), "purpose", s.purpose()));
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("correlation_id", s.correlationId());
    if (s.causationId() != null) trace.put("causation_id", s.causationId());
    trace.put("schema_version", SCHEMA_VERSION);
    env.put("trace", trace);
    env.put("replay", s.replay());
    return env;
  }

  /** Cabeçalhos Kafka do envelope (valores em texto). */
  public static Map<String, String> headers(Spec s, String connectorId) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("ce_id", s.eventId());
    h.put("ce_type", s.eventType());
    h.put("ce_source", connectorId);
    h.put("tenant_id", s.tenantId());
    h.put("correlation_id", s.correlationId());
    if (s.causationId() != null) h.put("causation_id", s.causationId());
    h.put("schema_version", SCHEMA_VERSION);
    h.put("replay", String.valueOf(s.replay()));
    return h;
  }
}
