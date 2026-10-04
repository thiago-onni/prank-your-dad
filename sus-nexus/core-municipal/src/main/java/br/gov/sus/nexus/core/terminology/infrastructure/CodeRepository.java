package br.gov.sus.nexus.core.terminology.infrastructure;

import br.gov.sus.nexus.core.terminology.domain.Code;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Repositório de códigos de terminologia. */
@ApplicationScoped
public class CodeRepository implements PanacheRepositoryBase<Code, Long> {

  /** Versão vigente do código na competência (ou qualquer versão se competência nula). */
  public Optional<Code> findCurrent(String system, String code, String competence) {
    if (competence == null) {
      return find("system = ?1 and code = ?2 order by competenceFrom desc", system, code)
          .firstResultOptional();
    }
    return find(
            "system = ?1 and code = ?2 and competenceFrom <= ?3"
                + " and (competenceTo is null or competenceTo >= ?3) order by competenceFrom desc",
            system,
            code,
            competence)
        .firstResultOptional();
  }

  public Optional<Code> findExact(String system, String code, String competenceFrom) {
    return find("system = ?1 and code = ?2 and competenceFrom = ?3", system, code, competenceFrom)
        .firstResultOptional();
  }

  public List<Code> search(
      String system, String q, String code, String competence, Long afterId, int limitPlusOne) {
    StringBuilder sql = new StringBuilder("select * from terminology.code c where c.system = ?1");
    List<Object> params = new ArrayList<>();
    params.add(system);
    if (code != null && !code.isBlank()) {
      params.add(code.trim());
      sql.append(" and c.code = ?").append(params.size());
    }
    if (q != null && !q.isBlank()) {
      params.add(q.trim().toLowerCase());
      int idx = params.size();
      sql.append(" and (c.display_norm % platform.immutable_unaccent(?")
          .append(idx)
          .append(") or c.display_norm like '%' || platform.immutable_unaccent(?")
          .append(idx)
          .append(") || '%')");
    }
    if (competence != null && !competence.isBlank()) {
      params.add(competence.trim());
      int idx = params.size();
      sql.append(" and c.competence_from <= ?")
          .append(idx)
          .append(" and (c.competence_to is null or c.competence_to >= ?")
          .append(idx)
          .append(")");
    }
    if (afterId != null) {
      params.add(afterId);
      sql.append(" and c.id > ?").append(params.size());
    }
    sql.append(" order by c.id limit ").append(limitPlusOne);
    Query query = getEntityManager().createNativeQuery(sql.toString(), Code.class);
    for (int i = 0; i < params.size(); i++) {
      query.setParameter(i + 1, params.get(i));
    }
    @SuppressWarnings("unchecked")
    List<Code> result = query.getResultList();
    return result;
  }
}
