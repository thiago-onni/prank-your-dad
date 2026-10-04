package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** Lote de upsert de unidades (chave natural: {@code cnes}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HealthUnitUpsertBatch(
    SourceRef source, String competence, List<HealthUnitUpsert> items) {}
