package br.gov.sus.nexus.core.exams.api;

/** Resultado do registro: pedido resultante e se foi criado (201) ou atualizado (200). */
public record ExamOrderResult(ExamOrderDto order, boolean created) {}
