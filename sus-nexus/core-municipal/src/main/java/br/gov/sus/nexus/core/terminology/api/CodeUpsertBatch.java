package br.gov.sus.nexus.core.terminology.api;

import br.gov.sus.nexus.core.platform.ingestion.BatchSource;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Lote de upsert de códigos de um sistema por competência (OpenAPI {@code CodeUpsertBatch}). {@code
 * competence} é a competência do arquivo (AAAAMM), usada como {@code competence_from} padrão.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CodeUpsertBatch(
    @NotNull @Valid BatchSource source,
    String competence,
    String version,
    @NotNull @Size(max = 1000) List<CodeUpsert> items) {}
