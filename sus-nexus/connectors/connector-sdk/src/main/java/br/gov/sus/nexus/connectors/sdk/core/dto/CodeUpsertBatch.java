package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * Lote de upsert de códigos ({@code POST /api/v1/terminology/{system}/codes/upsert}). {@code
 * competence} é a competência do arquivo (AAAAMM) e {@code version} a versão da tabela.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CodeUpsertBatch(
    SourceRef source, String competence, String version, List<CodeUpsert> items) {}
