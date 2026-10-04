package br.gov.sus.nexus.core.reference.api;

/** Comando de upsert idempotente de unidade por (tenant, cnes) — usado pelos conectores. */
public record HealthUnitUpsert(
    String cnes,
    String name,
    String kindCode,
    String kindDescription,
    String address,
    Boolean active,
    String sourceSystem) {}
