package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;

/** Identificador mascarado (OpenAPI {@code MaskedIdentifier}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MaskedIdentifier(
    String id,
    IdentifierSystem system,
    String valueMasked,
    String status,
    String sourceSystem,
    OffsetDateTime validFrom) {}
