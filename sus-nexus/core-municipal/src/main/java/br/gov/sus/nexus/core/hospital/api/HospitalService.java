package br.gov.sus.nexus.core.hospital.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * API pública do módulo hospital (porta única: REST, consumidor de ingestão e workflow). Episódios,
 * movimentações ADT, alta (gatilho do fluxo pós-alta, HOS-003/004/005), contrarreferência (HOS-008)
 * e desfecho do contato (CUI-006). Conteúdo clínico do sumário NUNCA é armazenado.
 */
public interface HospitalService {

  /**
   * Movimento ADT: {@code admit} cria/garante o episódio por vínculo de origem; {@code
   * transfer}/{@code bed_change} registram movimentação; {@code discharge}/{@code death} executam o
   * fluxo de alta; {@code cancel} encerra sem alta.
   */
  HospitalEpisodeResult register(HospitalMovementRegistration registration);

  /**
   * Alta: LOS, reinternação em 30 d, UBS/equipe de referência (identity), risco por regra
   * versionada (HOS-005), evento {@code sus.hospital.discharge.completed}, tarefa {@code
   * post_discharge_followup}.
   */
  HospitalEpisodeDto discharge(String episodeId, DischargeRegistration discharge);

  HospitalEpisodeDto dischargeBySource(
      String sourceSystem, String sourceRecordId, DischargeRegistration discharge);

  HospitalEpisodeDto counterReferral(String episodeId, CounterReferralRegistration registration);

  /** Desfecho do contato pós-alta: conclui a tarefa, sinaliza o workflow e pode abrir plano. */
  HospitalEpisodeDto followup(String episodeId, DischargeFollowup followup);

  /** Leitura com CID conforme papel e vínculo do ator (highly_restricted: só equipe/hospital). */
  HospitalEpisodeDto get(String episodeId);

  Optional<HospitalEpisodeDto> findBySource(String sourceSystem, String sourceRecordId);

  Page<HospitalEpisodeDto> list(
      String citizenId,
      String hospitalCnes,
      HospitalEpisodeStatus status,
      OffsetDateTime dischargedFrom,
      OffsetDateTime dischargedTo,
      String referenceCnes,
      String followupStatus,
      String cursor,
      Integer limit);

  /** Última alta do cidadão (resumo operacional). */
  Optional<OffsetDateTime> lastDischargeAt(String citizenId);

  // --- suporte ao DischargeFollowUpWorkflow (activities idempotentes) ---

  /** Estado do acompanhamento pós-alta para o workflow. */
  record FollowupSnapshot(
      String status, String dueAt, boolean contacted, boolean closed, String riskLevel) {}

  Optional<FollowupSnapshot> followupSnapshot(String episodeId);

  /**
   * Prazo sem contato: tarefa escalonada, nova tarefa {@code active_search} para a microárea e
   * lacuna {@code post_discharge_no_contact}. Idempotente; false se já contatado/encerrado.
   */
  boolean escalateFollowup(String episodeId);

  /** Segundo prazo sem contato: encerra o acompanhamento como {@code not_found}. */
  boolean closeFollowupNotFound(String episodeId);
}
