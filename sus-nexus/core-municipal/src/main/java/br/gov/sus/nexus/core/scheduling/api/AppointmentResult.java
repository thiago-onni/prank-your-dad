package br.gov.sus.nexus.core.scheduling.api;

/** Resultado do registro: agendamento resultante e se foi criado (201) ou atualizado (200). */
public record AppointmentResult(AppointmentDto appointment, boolean created) {}
