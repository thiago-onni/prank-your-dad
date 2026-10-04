package br.gov.sus.nexus.core.audit.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.time.OffsetDateTime;

/** Trilha de acessos (LGPD): quem leu o quê, com qual finalidade. */
public interface AccessLogService {

  /** Grava em transação própria (REQUIRES_NEW) para sobreviver a rollback da operação. */
  String record(AccessRecord record);

  Page<AccessLogEntry> list(
      String citizenId,
      String actorId,
      OffsetDateTime from,
      OffsetDateTime to,
      String cursor,
      Integer limit);
}
