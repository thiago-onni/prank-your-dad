package br.gov.sus.nexus.connectors.sdk.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Mensagem bruta exatamente como veio da fonte. É persistida na raw zone antes de qualquer
 * transformação. {@code sourceRecordId} identifica o registro (ou arquivo) na fonte.
 */
public record RawMessage(
    String sourceRecordId,
    String sourceRecordVersion,
    String entityType,
    String contentType,
    byte[] content,
    Map<String, String> metadata,
    Instant receivedAt) {

  public RawMessage {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    receivedAt = receivedAt == null ? Instant.now() : receivedAt;
    content = content == null ? new byte[0] : content.clone();
  }

  public static RawMessage ofText(
      String sourceRecordId, String entityType, String text, Map<String, String> metadata) {
    return new RawMessage(
        sourceRecordId,
        null,
        entityType,
        "text/plain; charset=utf-8",
        text.getBytes(StandardCharsets.UTF_8),
        metadata,
        Instant.now());
  }

  public String contentAsString() {
    return new String(content, StandardCharsets.UTF_8);
  }

  public RawMessage withVersion(String version) {
    return new RawMessage(
        sourceRecordId, version, entityType, contentType, content, metadata, receivedAt);
  }
}
