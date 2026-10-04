package br.gov.sus.nexus.core.hospital.api;

/** Resultado do registro ADT: episódio e se foi criado agora. */
public record HospitalEpisodeResult(HospitalEpisodeDto episode, boolean created) {}
