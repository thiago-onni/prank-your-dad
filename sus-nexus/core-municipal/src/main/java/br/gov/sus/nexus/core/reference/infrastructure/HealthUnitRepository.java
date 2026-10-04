package br.gov.sus.nexus.core.reference.infrastructure;

import br.gov.sus.nexus.core.reference.domain.HealthUnit;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Optional;

/** Repositório Panache de unidades de saúde (RLS garante o tenant). */
@ApplicationScoped
public class HealthUnitRepository implements PanacheRepositoryBase<HealthUnit, String> {

  public Optional<HealthUnit> findByCnes(String tenantId, String cnes) {
    return find("tenantId = ?1 and cnes = ?2", tenantId, cnes).firstResultOptional();
  }

  /** Busca por nome (trigram + unaccent) e/ou CNES, ordenada por id (keyset). */
  public List<HealthUnit> search(String q, String cnes, String afterId, int limitPlusOne) {
    StringBuilder sql = new StringBuilder("select * from reference.health_unit hu where 1=1");
    List<Object> params = new java.util.ArrayList<>();
    if (q != null && !q.isBlank()) {
      params.add(q.trim().toLowerCase());
      sql.append(" and platform.immutable_unaccent(lower(hu.name)) % platform.immutable_unaccent(?")
          .append(params.size())
          .append(")");
    }
    if (cnes != null && !cnes.isBlank()) {
      params.add(cnes.trim());
      sql.append(" and hu.cnes = ?").append(params.size());
    }
    if (afterId != null) {
      params.add(afterId);
      sql.append(" and hu.id > ?").append(params.size());
    }
    sql.append(" order by hu.id limit ").append(limitPlusOne);
    Query query = getEntityManager().createNativeQuery(sql.toString(), HealthUnit.class);
    for (int i = 0; i < params.size(); i++) {
      query.setParameter(i + 1, params.get(i));
    }
    @SuppressWarnings("unchecked")
    List<HealthUnit> result = query.getResultList();
    return result;
  }
}
