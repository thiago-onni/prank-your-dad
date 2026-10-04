package br.gov.sus.nexus.core.reference.application;

import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.ingestion.UpsertResult;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.reference.api.HealthUnitDto;
import br.gov.sus.nexus.core.reference.api.HealthUnitService;
import br.gov.sus.nexus.core.reference.api.HealthUnitUpsert;
import br.gov.sus.nexus.core.reference.api.HealthUnitUpsertBatch;
import br.gov.sus.nexus.core.reference.domain.HealthUnit;
import br.gov.sus.nexus.core.reference.infrastructure.HealthUnitRepository;
import br.gov.sus.nexus.core.sharedkernel.Cnes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Upsert idempotente por (tenant, cnes) e consultas de unidades. */
@ApplicationScoped
public class HealthUnitServiceImpl implements HealthUnitService {

  @Inject HealthUnitRepository repository;
  @Inject TenantContext tenantContext;

  /** Resultado interno de um upsert unitário. */
  enum Outcome {
    CREATED,
    UPDATED,
    UNCHANGED
  }

  @Override
  @TenantTransactional
  public HealthUnitDto upsert(HealthUnitUpsert cmd) {
    if (!Cnes.isValid(cmd.cnes())) {
      throw DomainValidationException.field("cnes", "CNES deve ter 7 dígitos");
    }
    if (cmd.name() == null || cmd.name().isBlank()) {
      throw DomainValidationException.field("name", "obrigatório");
    }
    return toDto(apply(cmd, null).hu());
  }

  @Override
  @TenantTransactional
  public UpsertResult upsertBatch(HealthUnitUpsertBatch batch) {
    UpsertResult.Counter counter = new UpsertResult.Counter();
    String defaultSource = batch.source() == null ? null : batch.source().system();
    for (HealthUnitUpsert item : batch.items()) {
      if (item == null
          || !Cnes.isValid(item.cnes())
          || item.name() == null
          || item.name().isBlank()) {
        counter.rejected();
        continue;
      }
      HealthUnitUpsert cmd =
          item.sourceSystem() == null
              ? new HealthUnitUpsert(
                  item.cnes(),
                  item.name(),
                  item.kindCode(),
                  item.kindDescription(),
                  item.address(),
                  item.cityIbge(),
                  item.active(),
                  item.competence() == null ? batch.competence() : item.competence(),
                  item.attributes(),
                  defaultSource)
              : item;
      switch (apply(cmd, batch.competence()).outcome()) {
        case CREATED -> counter.created();
        case UPDATED -> counter.updated();
        case UNCHANGED -> counter.unchanged();
      }
    }
    return counter.result();
  }

  record Applied(HealthUnit hu, Outcome outcome) {}

  private Applied apply(HealthUnitUpsert cmd, String batchCompetence) {
    String tenant = tenantContext.require();
    Optional<HealthUnit> existing = repository.findByCnes(tenant, cmd.cnes().trim());
    HealthUnit hu =
        existing.orElseGet(
            () -> {
              HealthUnit n = new HealthUnit();
              n.id = Ulid.generate(Ulid.HEALTH_UNIT);
              n.tenantId = tenant;
              n.cnes = cmd.cnes().trim();
              return n;
            });
    boolean changed = existing.isEmpty();
    changed |= set(hu.name, cmd.name().trim(), v -> hu.name = v);
    changed |= set(hu.kindCode, cmd.kindCode(), v -> hu.kindCode = v);
    changed |= set(hu.kindDescription, cmd.kindDescription(), v -> hu.kindDescription = v);
    changed |= set(hu.address, cmd.address(), v -> hu.address = v);
    changed |= set(hu.cityIbge, cmd.cityIbge(), v -> hu.cityIbge = v);
    changed |= set(hu.active, cmd.active(), v -> hu.active = v);
    changed |= set(hu.sourceSystem, cmd.sourceSystem(), v -> hu.sourceSystem = v);
    String competence = cmd.competence() == null ? batchCompetence : cmd.competence();
    changed |= set(hu.competence, competence, v -> hu.competence = v);
    if (cmd.attributes() != null && !cmd.attributes().isEmpty()) {
      Map<String, Object> merged =
          new LinkedHashMap<>(hu.attributes == null ? Map.of() : hu.attributes);
      merged.putAll(cmd.attributes());
      changed |= set(hu.attributes, merged, v -> hu.attributes = v);
    }
    if (existing.isEmpty()) {
      repository.persist(hu);
      return new Applied(hu, Outcome.CREATED);
    }
    if (!changed) {
      return new Applied(hu, Outcome.UNCHANGED);
    }
    hu.updatedAt = Instant.now();
    return new Applied(hu, Outcome.UPDATED);
  }

  /** Aplica {@code value} quando não nulo e diferente do atual; retorna se houve mudança. */
  private static <T> boolean set(T current, T value, java.util.function.Consumer<T> setter) {
    if (value == null || Objects.equals(current, value)) {
      return false;
    }
    setter.accept(value);
    return true;
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
