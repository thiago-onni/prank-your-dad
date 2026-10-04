package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Pattern;

/** Exportação do lote (OpenAPI {@code ProductionBatchExportRequest}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionBatchExportRequest(
    @Pattern(regexp = "bpa_mag_v202412|apac_mag_v202607|csv_ref_v1") String layout) {}
