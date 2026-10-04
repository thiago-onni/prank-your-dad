package br.gov.sus.nexus.core.platform.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Envelope de evento conforme {@code contracts/events/envelope.schema.json}. Serializado em
 * snake_case, sem campos nulos ({@code additionalProperties: false} no schema).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EventEnvelope(
    String eventId,
    String eventType,
    String eventVersion,
    OffsetDateTime occurredAt,
    OffsetDateTime publishedAt,
    Tenant tenant,
    Subject subject,
    Source source,
    Map<String, Object> data,
    String dataRef,
    Privacy privacy,
    Trace trace,
    Boolean replay) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Tenant(String municipalityId, String healthSecretariatId) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Subject(String municipalCitizenId, List<SubjectIdentifier> identifiers) {}

  /** Identificador do sujeito: SOMENTE mascarado e/ou hash (nunca em claro). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SubjectIdentifier(String system, String valueMasked, String valueHash) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Source(
      String system,
      String connector,
      String sourceRecordId,
      String sourceRecordVersion,
      String cnes) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Privacy(String classification, List<String> purpose) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Trace(String correlationId, String causationId, String schemaVersion) {}
}
