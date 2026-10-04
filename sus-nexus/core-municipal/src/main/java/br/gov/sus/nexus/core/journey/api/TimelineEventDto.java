package br.gov.sus.nexus.core.journey.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;

/** Evento da linha do tempo (OpenAPI {@code TimelineEvent}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TimelineEventDto(
    String id,
    String citizenId,
    String domain,
    String eventType,
    OffsetDateTime occurredAt,
    OffsetDateTime recordedAt,
    String sourceSystem,
    String cnes,
    String healthUnitName,
    String professionalRef,
    String status,
    String confidence,
    String sensitivity,
    String summary,
    String detailRef,
    List<String> correlationChain) {

  /** Cópia com campos redigidos (obrigação {@code redact_fields} da política). */
  public TimelineEventDto redacted(List<String> fields) {
    if (fields == null || fields.isEmpty()) {
      return this;
    }
    boolean clinical =
        fields.stream()
            .anyMatch(
                f ->
                    f.equals("diagnoses")
                        || f.equals("results")
                        || f.equals("documents")
                        || f.equals("clinical_notes"));
    return new TimelineEventDto(
        id,
        citizenId,
        domain,
        eventType,
        occurredAt,
        recordedAt,
        sourceSystem,
        cnes,
        fields.contains("health_unit_name") ? null : healthUnitName,
        fields.contains("professional_ref") ? null : professionalRef,
        status,
        confidence,
        sensitivity,
        fields.contains("summary") ? null : summary,
        clinical || fields.contains("detail_ref") ? null : detailRef,
        correlationChain);
  }
}
