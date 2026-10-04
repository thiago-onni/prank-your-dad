package br.gov.sus.nexus.core.consent.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Consentimento registrado. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ConsentDto(
    String id,
    String citizenId,
    String purpose,
    String status,
    String channel,
    OffsetDateTime recordedAt,
    String source) {}
