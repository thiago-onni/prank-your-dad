package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.domain.CitizenMerge;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** Repositório de fusões efetivadas. */
@ApplicationScoped
public class MergeRepository implements PanacheRepositoryBase<CitizenMerge, String> {}
