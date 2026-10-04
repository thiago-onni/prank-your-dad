package br.gov.sus.nexus.core.consent.infrastructure;

import br.gov.sus.nexus.core.consent.domain.CommunicationPreference;
import br.gov.sus.nexus.core.consent.domain.Consent;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Repositórios Panache do módulo consent (RLS garante o tenant). */
public final class ConsentRepositories {

  private ConsentRepositories() {}

  @ApplicationScoped
  public static class Consents implements PanacheRepositoryBase<Consent, String> {
    public List<Consent> byCitizen(String citizenId) {
      return list("citizenId = ?1 order by recordedAt desc, id desc", citizenId);
    }

    public Optional<Consent> latest(String citizenId, String purpose) {
      return find("citizenId = ?1 and purpose = ?2 order by recordedAt desc, id desc", citizenId, purpose)
          .firstResultOptional();
    }
  }

  @ApplicationScoped
  public static class Preferences implements PanacheRepositoryBase<CommunicationPreference, String> {
    public List<CommunicationPreference> byCitizen(String citizenId) {
      return list("citizenId = ?1 order by preferred desc, updatedAt desc", citizenId);
    }

    public Optional<CommunicationPreference> find(String citizenId, String channel, String hash) {
      if (hash == null) {
        return find("citizenId = ?1 and channel = ?2 and valueHash is null", citizenId, channel)
            .firstResultOptional();
      }
      return find("citizenId = ?1 and channel = ?2 and valueHash = ?3", citizenId, channel, hash)
          .firstResultOptional();
    }
  }
}
