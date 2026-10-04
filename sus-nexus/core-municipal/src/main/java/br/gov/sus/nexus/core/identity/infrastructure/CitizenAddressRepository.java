package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.domain.CitizenAddress;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/** Repositório de endereços. */
@ApplicationScoped
public class CitizenAddressRepository implements PanacheRepositoryBase<CitizenAddress, String> {

  public Optional<CitizenAddress> findCurrent(String citizenId) {
    return find("citizenId = ?1 and current = true order by receivedAt desc", citizenId)
        .firstResultOptional();
  }
}
