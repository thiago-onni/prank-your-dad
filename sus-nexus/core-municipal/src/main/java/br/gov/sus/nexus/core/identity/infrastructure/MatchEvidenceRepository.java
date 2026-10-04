package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.domain.CitizenMatchEvidence;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Repositório de evidências de matching. */
@ApplicationScoped
public class MatchEvidenceRepository
    implements PanacheRepositoryBase<CitizenMatchEvidence, String> {

  public List<CitizenMatchEvidence> findByCandidates(List<String> candidateIds) {
    if (candidateIds.isEmpty()) {
      return List.of();
    }
    return list("matchCandidateId in ?1 order by matchCandidateId, attribute", candidateIds);
  }
}
