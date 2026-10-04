package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.domain.CitizenDemographicHistory;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/** Repositório do histórico demográfico bitemporal. */
@ApplicationScoped
public class DemographicHistoryRepository
    implements PanacheRepositoryBase<CitizenDemographicHistory, String> {

  /** Versão corrente (sem recorded_to). */
  public Optional<CitizenDemographicHistory> findCurrent(String citizenId) {
    return find("citizenId = ?1 and recordedTo is null order by recordedFrom desc", citizenId)
        .firstResultOptional();
  }
}
