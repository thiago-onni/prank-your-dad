package br.gov.sus.nexus.core.reference.application;

import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.reference.api.ProfessionalDirectory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Vínculos ativos profissional × unidade × CBO (RLS garante o tenant). */
@ApplicationScoped
public class ProfessionalDirectoryImpl implements ProfessionalDirectory {

  @Inject EntityManager entityManager;

  @Override
  @TenantTransactional
  @SuppressWarnings("unchecked")
  public Optional<Set<String>> activeCbos(String professionalCnsHash, String cnes) {
    if (professionalCnsHash == null || cnes == null) {
      return Optional.empty();
    }
    List<Object> known =
        entityManager
            .createNativeQuery(
                "select p.id from reference.professional p where p.cns_hash = ?1 and p.active")
            .setParameter(1, professionalCnsHash)
            .getResultList();
    if (known.isEmpty()) {
      return Optional.empty();
    }
    List<Object> cbos =
        entityManager
            .createNativeQuery(
                "select r.cbo from reference.professional_role r"
                    + " join reference.professional p on p.id = r.professional_id"
                    + " join reference.health_unit u on u.id = r.health_unit_id"
                    + " where p.cns_hash = ?1 and u.cnes = ?2 and r.active"
                    + " and (r.valid_to is null or r.valid_to > now())")
            .setParameter(1, professionalCnsHash)
            .setParameter(2, cnes)
            .getResultList();
    Set<String> out = new LinkedHashSet<>();
    cbos.forEach(c -> out.add(String.valueOf(c)));
    return Optional.of(out);
  }
}
