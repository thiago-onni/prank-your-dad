package br.gov.sus.nexus.core.production.api;

/** Resultado do registro por vínculo de origem: registro e se foi criado agora. */
public record ProductionRecordResult(ProductionRecordDto record, boolean created) {}
