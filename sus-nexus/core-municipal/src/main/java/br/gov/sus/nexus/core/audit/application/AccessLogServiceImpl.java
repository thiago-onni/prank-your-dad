package br.gov.sus.nexus.core.audit.application;

import br.gov.sus.nexus.core.audit.api.AccessLogEntry;
import br.gov.sus.nexus.core.audit.api.AccessLogService;
import br.gov.sus.nexus.core.audit.api.AccessRecord;
import br.gov.sus.nexus.core.audit.infrastructure.AccessLogRepository;
import br.gov.sus.nexus.core.platform.ids.Ulid;
import br.gov.sus.nexus.core.platform.pagination.Cursor;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.tenant.TenantContext;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactional;
import br.gov.sus.nexus.core.platform.tenant.TenantTransactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.TreeSet;

/** Implementação do access_log: escrita em transação própria; leitura paginada por cursor. */
@ApplicationScoped
public class AccessLogServiceImpl implements AccessLogService {

  @Inject AccessLogRepository repository;
  @Inject TenantContext tenantContext;
  @Inject TenantTransactions transactions;

  @Override
  public String record(AccessRecord r) {
    String tenant = tenantContext.require();
    String id = Ulid.generate(Ulid.ACCESS);
    String rolesCsv =
        String.join(",", new TreeSet<>(r.actorRoles() == null ? List.of() : r.actorRoles()));
    transactions.requiringNew(
        () -> {
          repository.insert(
              id,
              tenant,
              r.actorId(),
              rolesCsv,
              r.action(),
              r.resourceType(),
              r.resourceId(),
              r.citizenId(),
              r.purpose() != null ? r.purpose().wire() : null,
              r.allowed() ? "allow" : "deny",
              r.breakGlass(),
              r.justification(),
              r.correlationId());
          return null;
        });
    return id;
  }

  @Override
  @TenantTransactional
  public Page<AccessLogEntry> list(
      String citizenId,
      String actorId,
      OffsetDateTime from,
      OffsetDateTime to,
      String cursor,
      Integer limit) {
    int size = Cursor.limit(limit);
    String afterId = Cursor.decode(cursor).orElse(null);
    List<AccessLogEntry> rows = repository.list(citizenId, actorId, from, to, afterId, size + 1);
    return Page.of(rows, size, AccessLogEntry::id);
  }
}
