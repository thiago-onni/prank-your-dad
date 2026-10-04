package br.gov.sus.nexus.core.consent.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Preferência de comunicação (valor somente mascarado). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CommunicationPreferenceDto(
    String id,
    String citizenId,
    String channel,
    String valueMasked,
    boolean preferred,
    boolean allowed,
    String source,
    OffsetDateTime updatedAt) {}
