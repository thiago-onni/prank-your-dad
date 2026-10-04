package br.gov.sus.nexus.core.integration.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/** Entrada de reconciliação (OpenAPI {@code ReconciliationEntry}); também usado na escrita. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReconciliationEntryDto(
    String id,
    @NotBlank String connectorId,
    @NotBlank String entityType,
    @NotNull OffsetDateTime periodStart,
    @NotNull OffsetDateTime periodEnd,
    int sourceCount,
    int busCount,
    int gap,
    OffsetDateTime checkedAt) {}
