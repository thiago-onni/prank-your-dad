package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

/** Canônico {@code TimelineEvent} de {@code contracts/openapi/core-municipal.yaml}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalTimelineEvent(
    String id,
    String citizenId,
    String domain,
    String eventType,
    Instant occurredAt,
    Instant recordedAt,
    String sourceSystem,
    String cnes,
    String healthUnitName,
    String professionalRef,
    String status,
    String confidence,
    String sensitivity,
    String summary,
    String detailRef) {}
