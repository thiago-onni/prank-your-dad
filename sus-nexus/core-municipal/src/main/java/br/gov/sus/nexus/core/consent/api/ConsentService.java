package br.gov.sus.nexus.core.consent.api;

import java.util.List;

/**
 * API interna do módulo consent (mínimo da Fase 3): consentimentos por finalidade e preferências de
 * comunicação. Sem REST nesta fase (Fase 3b); consumido por careplan/journey ({@code
 * contact_valid}).
 */
public interface ConsentService {

  ConsentDto record(ConsentRecord record);

  CommunicationPreferenceDto setPreference(PreferenceUpsert upsert);

  List<ConsentDto> consents(String citizenId);

  List<CommunicationPreferenceDto> preferences(String citizenId);

  /**
   * Contato válido para busca ativa: nenhum consentimento de comunicação revogado E (há preferência
   * permitida com valor OU, sem preferências registradas, o cadastro possui contato).
   */
  boolean contactValid(String citizenId);
}
