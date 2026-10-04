package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.domain.Citizen;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repositório de cidadãos (RLS garante o tenant; filtros explícitos por defesa em profundidade).
 */
@ApplicationScoped
public class CitizenRepository implements PanacheRepositoryBase<Citizen, String> {

  /** Regra determinística (d): nome normalizado + data de nascimento + nome da mãe normalizado. */
  public Optional<Citizen> findByDemographics(
      String tenantId, String normalizedName, LocalDate birthdate, String normalizedMotherName) {
    return find(
            "tenantId = ?1 and status = 'active' and normalizedName = ?2 and birthdate = ?3"
                + " and normalizedMotherName = ?4 order by id",
            tenantId,
            normalizedName,
            birthdate,
            normalizedMotherName)
        .firstResultOptional();
  }

  /**
   * Blocking para o matching probabilístico: mesma data de nascimento OU nome normalizado similar
   * (trigram). Ordenado pela melhor afinidade, limitado.
   */
  public List<Citizen> blockingCandidates(
      String tenantId,
      String normalizedName,
      LocalDate birthdate,
      double minSimilarity,
      int limit) {
    String sql =
        "select c.* from identity.citizen c where c.tenant_id = ?1 and c.status = 'active'"
            + " and (c.birthdate = ?2 or similarity(c.normalized_name, ?3) >= ?4)"
            + " order by greatest(similarity(c.normalized_name, ?3),"
            + " case when c.birthdate = ?2 then 0.5 else 0 end) desc, c.id limit ?5";
    Query query =
        getEntityManager()
            .createNativeQuery(sql, Citizen.class)
            .setParameter(1, tenantId)
            .setParameter(2, birthdate)
            .setParameter(3, normalizedName)
            .setParameter(4, (float) minSimilarity)
            .setParameter(5, limit);
    @SuppressWarnings("unchecked")
    List<Citizen> result = query.getResultList();
    return result;
  }

  /** Busca textual (trigram + unaccent) sobre nome, nome social e nome da mãe normalizados. */
  public List<Citizen> searchByText(
      String normalizedQuery,
      LocalDate birthdate,
      String registrationState,
      int offset,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "select c.* from identity.citizen c where (c.normalized_name % ?1"
                + " or c.normalized_social_name % ?1 or c.normalized_mother_name % ?1)");
    List<Object> params = new ArrayList<>();
    params.add(normalizedQuery);
    if (birthdate != null) {
      params.add(birthdate);
      sql.append(" and c.birthdate = ?").append(params.size());
    }
    if (registrationState != null) {
      params.add(registrationState);
      sql.append(" and c.registration_state = ?").append(params.size());
    }
    sql.append(
            " order by greatest(similarity(c.normalized_name, ?1),"
                + " coalesce(similarity(c.normalized_social_name, ?1), 0),"
                + " coalesce(similarity(c.normalized_mother_name, ?1), 0)) desc, c.id")
        .append(" offset ")
        .append(offset)
        .append(" limit ")
        .append(limit);
    Query query = getEntityManager().createNativeQuery(sql.toString(), Citizen.class);
    for (int i = 0; i < params.size(); i++) {
      query.setParameter(i + 1, params.get(i));
    }
    @SuppressWarnings("unchecked")
    List<Citizen> result = query.getResultList();
    return result;
  }

  /** Listagem keyset por id (sem texto). */
  public List<Citizen> listKeyset(
      LocalDate birthdate, String registrationState, String afterId, int limitPlusOne) {
    StringBuilder jpql = new StringBuilder("1 = 1");
    List<Object> params = new ArrayList<>();
    if (birthdate != null) {
      params.add(birthdate);
      jpql.append(" and birthdate = ?").append(params.size());
    }
    if (registrationState != null) {
      params.add(registrationState);
      jpql.append(" and registrationState = ?").append(params.size());
    }
    if (afterId != null) {
      params.add(afterId);
      jpql.append(" and id > ?").append(params.size());
    }
    jpql.append(" order by id");
    return find(jpql.toString(), params.toArray()).page(0, limitPlusOne).list();
  }

  /** Segue a cadeia {@code merged_into_id} até o cidadão sobrevivente (limite defensivo). */
  public Citizen resolveMerged(Citizen citizen) {
    Citizen current = citizen;
    int hops = 0;
    while (current != null && current.isMerged() && current.mergedIntoId != null && hops++ < 10) {
      current = findById(current.mergedIntoId);
    }
    return current == null ? citizen : current;
  }
}
