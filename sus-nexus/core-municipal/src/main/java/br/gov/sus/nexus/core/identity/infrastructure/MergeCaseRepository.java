package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.domain.CitizenMergeCase;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/** Repositório de casos de fusão. */
@ApplicationScoped
public class MergeCaseRepository implements PanacheRepositoryBase<CitizenMergeCase, String> {

  /** Lista por status, mais recentes primeiro (keyset {@code id < cursor}). */
  public List<CitizenMergeCase> list(String status, String beforeId, int limitPlusOne) {
    StringBuilder jpql = new StringBuilder("1 = 1");
    List<Object> params = new ArrayList<>();
    if (status != null) {
      params.add(status);
      jpql.append(" and status = ?").append(params.size());
    }
    if (beforeId != null) {
      params.add(beforeId);
      jpql.append(" and id < ?").append(params.size());
    }
    jpql.append(" order by id desc");
    return find(jpql.toString(), params.toArray()).page(0, limitPlusOne).list();
  }
}
