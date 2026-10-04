package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.domain.CitizenGoldenRecordAttribute;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Repositório da proveniência por atributo. */
@ApplicationScoped
public class GoldenRecordRepository
    implements PanacheRepositoryBase<CitizenGoldenRecordAttribute, String> {

  public List<CitizenGoldenRecordAttribute> findByCitizen(String citizenId) {
    return list("citizenId = ?1 order by attribute", citizenId);
  }

  public Optional<CitizenGoldenRecordAttribute> findOne(String citizenId, String attribute) {
    return find("citizenId = ?1 and attribute = ?2", citizenId, attribute).firstResultOptional();
  }
}
