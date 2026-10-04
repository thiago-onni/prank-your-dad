package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.domain.CitizenIdentifier;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Repositório de identificadores (busca por value_hash). */
@ApplicationScoped
public class CitizenIdentifierRepository
    implements PanacheRepositoryBase<CitizenIdentifier, String> {

  public Optional<CitizenIdentifier> findActive(String tenantId, String system, String valueHash) {
    return find(
            "tenantId = ?1 and system = ?2 and valueHash = ?3 and status = 'active'",
            tenantId,
            system,
            valueHash)
        .firstResultOptional();
  }

  public List<CitizenIdentifier> findByHash(String tenantId, String system, String valueHash) {
    return list("tenantId = ?1 and system = ?2 and valueHash = ?3", tenantId, system, valueHash);
  }

  public List<CitizenIdentifier> findByCitizen(String citizenId) {
    return list("citizenId = ?1 order by system, validFrom", citizenId);
  }

  public Optional<CitizenIdentifier> findByIdAndCitizen(String id, String citizenId) {
    return find("id = ?1 and citizenId = ?2", id, citizenId).firstResultOptional();
  }
}
