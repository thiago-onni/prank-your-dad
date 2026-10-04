package br.gov.sus.nexus.core.reference.api;

import br.gov.sus.nexus.core.platform.ingestion.BatchSource;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Lote de upsert de unidades (OpenAPI {@code HealthUnitUpsertBatch}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HealthUnitUpsertBatch(
    @NotNull @Valid BatchSource source,
    String competence,
    @NotNull @Size(max = 1000) List<HealthUnitUpsert> items) {}
