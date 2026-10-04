package br.gov.sus.nexus.core.identity.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.time.LocalDate;

/** API pública do MPI para cidadãos. */
public interface CitizenService {

  /** Porta única de entrada: resolve identidade (determinístico → probabilístico → novo). */
  IdentityResolution register(CitizenRegistration registration);

  CitizenDetail get(String citizenId);

  /**
   * Busca: {@code q} usa trigram+unaccent sobre nomes normalizados; {@code identifier} ({@code
   * CNS|valor}) usa value_hash.
   */
  Page<CitizenSummary> search(
      String q,
      String identifier,
      LocalDate birthdate,
      RegistrationState registrationState,
      String cursor,
      Integer limit);

  /** Revela identificador em claro; registra access_log e audit_log com finalidade. */
  Requests.Revealed reveal(String citizenId, String identifierId, Requests.Reveal request);
}
