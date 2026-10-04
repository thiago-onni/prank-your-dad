package br.gov.sus.nexus.core.regulation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Map;

/** Indicadores da fila por agrupamento (OpenAPI {@code RegulationQueueItem}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegulationQueueItemDto(
    String groupKey,
    String groupLabel,
    long openRequests,
    Map<String, Long> byPriority,
    Double avgWaitingDays,
    Double p90WaitingDays,
    long slaBreached,
    long withIssues,
    long scheduled30d,
    long noShow30d,
    Integer capacityAvailable) {}
