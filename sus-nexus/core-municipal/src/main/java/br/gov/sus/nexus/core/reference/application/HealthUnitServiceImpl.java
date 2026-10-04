package br.gov.sus.nexus.core.reference.application;

import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.reference.api.HealthUnitDto;
import br.gov.sus.nexus.core.reference.api.HealthUnitService;
import br.gov.sus.nexus.core.reference.api.HealthUnitUpsert;
import br.gov.sus.nexus.core.reference.domain.HealthUnit;
import br.gov.sus.nexus.core.reference.infrastructure.HealthUnitRepository;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Upsert idempotente por (tenant, cnes) e consultas de unidades. */
@ApplicationScoped
public class HealthUnitServiceImpl implements HealthUnitService {

  @Inject HealthUnitRepository repository;
  @Inject TenantContext tenantContext;

  @Override
  @TenantTransactional
  public HealthUnitDto upsert(HealthUnitUpsert cmd) {
    if (!Cnes.isValid(cmd.cnes())) {
      throw DomainValidationException.field("cnes", "CNES deve ter 7 dígitos");
    }
    if (cmd.name() == null || cmd.name().isBlank()) {
      throw DomainValidationException.field("name", "obrigatório");
    }
    String tenant = tenantContext.require();
    HealthUnit hu =
        repository
            .findByCnes(tenant, cmd.cnes().trim())
            .orElseGet(
                () -> {
                  HealthUnit n = new HealthUnit();
                  n.id = Ulid.generate(Ulid.HEALTH_UNIT);
                  n.tenantId = tenant;
                  n.cnes = cmd.cnes().trim();
                  return n;
                });
    hu.name = cmd.name().trim();
    if (cmd.kindCode() != null) {
      hu.kindCode = cmd.kindCode();
    }
    if (cmd.kindDescription() != null) {
      hu.kindDescription = cmd.kindDescription();
    }
    if (cmd.address() != null) {
      hu.address = cmd.address();
    }
    if (cmd.active() != null) {
      hu.active = cmd.active();
    }
    if (cmd.sourceSystem() != null) {
      hu.sourceSystem = cmd.sourceSystem();
    }
    hu.updatedAt = Instant.now();
    repository.persist(hu);
    return toDto(hu);
  }

  @Override
  @TenantTransactional
  public Optional<HealthUnitDto> findByCnes(String cnes) {
    return repository.findByCnes(tenantContext.require(), cnes).map(HealthUnitServiceImpl::toDto);
  }

  @Override
  @TenantTransactional
  public Page<HealthUnitDto> search(String q, String cnes, String cursor, Integer limit) {
    int size = Cursor.limit(limit);
    String afterId = Cursor.decode(cursor).orElse(null);
    List<HealthUnitDto> rows =
        repository.search(q, cnes, afterId, size + 1).stream()
            .map(HealthUnitServiceImpl::toDto)
            .toList();
    return Page.of(rows, size, HealthUnitDto::id);
  }

  static HealthUnitDto toDto(HealthUnit hu) {
    return new HealthUnitDto(
        hu.id, hu.cnes, hu.name, hu.kindCode, hu.kindDescription, hu.address, hu.active);
  }
}
