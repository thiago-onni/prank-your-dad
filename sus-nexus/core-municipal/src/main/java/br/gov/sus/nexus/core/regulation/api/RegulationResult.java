package br.gov.sus.nexus.core.regulation.api;

/** Resultado do registro: pedido resultante e se foi criado (201) ou atualizado (200). */
public record RegulationResult(RegulationRequestDto request, boolean created) {}
