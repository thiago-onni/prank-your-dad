package br.gov.sus.nexus.core.support;

import br.gov.sus.nexus.core.platform.ids.Ulid;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monta envelopes (contracts/events/envelope.schema.json) para alimentar consumidores em testes.
 */
public final class Envelopes {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Envelopes() {}

  public static String build(
      String tenant, String eventType, Map<String, Object> data, String citizenId) {
    return build(tenant, eventType, data, citizenId, Ulid.generate(Ulid.EVENT), null);
  }

  public static String build(
      String tenant,
      String eventType,
      Map<String, Object> data,
      String citizenId,
      String eventId,
      String causationId) {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("event_id", eventId);
    env.put("event_type", eventType);
    env.put("event_version", "1.0");
    env.put("occurred_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    env.put("published_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    env.put("tenant", Map.of("municipality_id", tenant));
    if (citizenId != null) {
      env.put("subject", Map.of("municipal_citizen_id", citizenId));
    }
    env.put(
        "source",
        Map.of(
            "system", "TEST", "connector", "connector-test", "source_record_id", "SRC-" + eventId));
    env.put("data", data);
    env.put(
        "privacy", Map.of("classification", "internal", "purpose", List.of("care_coordination")));
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("correlation_id", "corr_test_" + eventId.toLowerCase());
    if (causationId != null) {
      trace.put("causation_id", causationId);
    }
    trace.put("schema_version", "1.0.0");
    env.put("trace", trace);
    env.put("replay", false);
    try {
      return MAPPER.writeValueAsString(env);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
