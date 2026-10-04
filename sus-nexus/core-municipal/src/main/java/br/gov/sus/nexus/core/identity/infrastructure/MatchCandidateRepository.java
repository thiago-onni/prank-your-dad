package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.domain.CitizenMatchCandidate;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Repositório de pares avaliados pelo MPI. */
@ApplicationScoped
public class MatchCandidateRepository
    implements PanacheRepositoryBase<CitizenMatchCandidate, String> {

  public List<CitizenMatchCandidate> findByCase(String caseId) {
    return list("caseId = ?1 order by score desc, id", caseId);
  }
}
