package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.domain.CitizenSourceLink;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Repositório de vínculos de origem. */
@ApplicationScoped
public class CitizenSourceLinkRepository
    implements PanacheRepositoryBase<CitizenSourceLink, String> {

  public Optional<CitizenSourceLink> findBySource(
      String tenantId, String sourceSystem, String sourceRecordId) {
    return find(
            "tenantId = ?1 and sourceSystem = ?2 and sourceRecordId = ?3",
            tenantId,
            sourceSystem,
            sourceRecordId)
        .firstResultOptional();
  }

  public List<CitizenSourceLink> findByCitizen(String citizenId) {
    return list("citizenId = ?1 order by receivedAt", citizenId);
  }
}
