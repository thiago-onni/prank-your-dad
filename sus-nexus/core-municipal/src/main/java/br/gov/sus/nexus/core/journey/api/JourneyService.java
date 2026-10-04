package br.gov.sus.nexus.core.journey.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.time.OffsetDateTime;
import java.util.List;

/** API pública do módulo journey (linha do tempo e resumo operacional). */
public interface JourneyService {

  /**
   * Linha do tempo filtrada no servidor: cursor keyset por (occurred_at, id) decrescente; eventos
   * filtrados por sensibilidade conforme {@code AuthorizationPolicy} e campos redigidos conforme
   * obrigações.
   */
  Page<TimelineEventDto> timeline(
      String citizenId,
      OffsetDateTime from,
      OffsetDateTime to,
      List<String> domains,
      String cnes,
      String status,
      String cursor,
      Integer limit);

  CitizenOperationalSummary summary(String citizenId);
}
