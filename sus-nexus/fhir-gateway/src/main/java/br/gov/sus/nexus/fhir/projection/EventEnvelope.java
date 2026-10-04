package br.gov.sus.nexus.fhir.projection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/** Envelope padrão de evento ({@code contracts/events/envelope.schema.json}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
    String eventId,
    String eventType,
    String eventVersion,
    Instant occurredAt,
    Instant publishedAt,
    Tenant tenant,
    Subject subject,
    Source source,
    JsonNode data,
    Privacy privacy,
    Trace trace,
    Boolean replay) {

  /** Tenant do evento. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Tenant(String municipalityId, String healthSecretariatId) {}

  /** Cidadão a que o evento se refere (nunca CPF/CNS em claro). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Subject(String municipalCitizenId, List<JsonNode> identifiers) {}

  /** Origem do evento. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Source(
      String system,
      String connector,
      String sourceRecordId,
      String sourceRecordVersion,
      String cnes) {}

  /** Classificação e finalidades. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Privacy(String classification, List<String> purpose) {}

  /** Rastreabilidade. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Trace(String correlationId, String causationId, String schemaVersion) {}

  public String dataText(String field) {
    if (data == null || data.get(field) == null || data.get(field).isNull()) {
      return null;
    }
    return data.get(field).asText();
  }
}
