package br.gov.sus.nexus.core.reference.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.util.Optional;

/** API pública do módulo reference para unidades de saúde. */
public interface HealthUnitService {

  /** Upsert idempotente por (tenant, cnes). Retorna a unidade resultante. */
  HealthUnitDto upsert(HealthUnitUpsert command);

  Optional<HealthUnitDto> findByCnes(String cnes);

  Page<HealthUnitDto> search(String q, String cnes, String cursor, Integer limit);
}
