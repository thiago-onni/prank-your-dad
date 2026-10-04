package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Retorno do processamento oficial (PRO-008; OpenAPI {@code ProductionOutcomeRegistration}).
 * Exatamente um entre {@code production_record_id}, {@code record_source} e {@code batch_id}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionOutcomeRegistration(
    @NotNull @Valid ProductionSource source,
    @NotBlank @Pattern(regexp = "transmitted|received|accepted|rejected|paid") String outcome,
    String productionRecordId,
    ExternalRef recordSource,
    String batchId,
    @NotNull OffsetDateTime processedAt,
    @Size(max = 32) String reasonCode,
    @Size(max = 500) String reason,
    @DecimalMin("0") BigDecimal paidAmount,
    @Min(0) Integer approvedQuantity,
    @Size(max = 64) String protocolNumber) {}
