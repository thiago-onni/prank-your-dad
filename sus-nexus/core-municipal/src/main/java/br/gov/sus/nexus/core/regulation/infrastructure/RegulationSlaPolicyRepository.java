package br.gov.sus.nexus.core.regulation.infrastructure;

import br.gov.sus.nexus.core.regulation.api.RegulationPriority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Políticas de SLA de decisão por prioridade ({@code regulation.regulation_sla_policy}): a RLS
 * expõe as globais ({@code tenant_id IS NULL}) e as do tenant; a do tenant tem precedência.
 */
@ApplicationScoped
public class RegulationSlaPolicyRepository {

  /** Política vigente: id + prazo. */
  public record Policy(String id, Duration dueIn, String version) {}

  private static final String SQL =
      "select id, extract(epoch from due_in)::bigint, policy_version"
          + " from regulation.regulation_sla_policy where active and priority = ?1"
          + " and effective_from <= now() and (effective_to is null or effective_to > now())"
          + " order by (tenant_id is null), effective_from desc limit 1";

  @Inject EntityManager entityManager;

  public Optional<Policy> resolve(RegulationPriority priority) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager.createNativeQuery(SQL).setParameter(1, priority.wire()).getResultList();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] r = rows.get(0);
    return Optional.of(
        new Policy((String) r[0], Duration.ofSeconds(((Number) r[1]).longValue()), (String) r[2]));
  }
}
