package br.gov.sus.nexus.core.regulation.api;

/** Filtros da fila regulatória ({@code GET /regulation/requests}, REG-004). */
public record RegulationQuery(
    String citizenId,
    RegulationStatus status,
    RegulationPriority priority,
    String serviceCode,
    String specialty,
    String requestingCnes,
    String providerCnes,
    String territory,
    String issue,
    String sort,
    String cursor,
    Integer limit) {}
