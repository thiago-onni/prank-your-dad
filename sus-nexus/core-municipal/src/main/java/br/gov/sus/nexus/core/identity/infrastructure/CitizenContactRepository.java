package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.domain.CitizenContact;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Repositório de contatos. */
@ApplicationScoped
public class CitizenContactRepository implements PanacheRepositoryBase<CitizenContact, String> {

  public List<CitizenContact> findByCitizen(String citizenId) {
    return list("citizenId = ?1 order by preferred desc, receivedAt", citizenId);
  }
}
