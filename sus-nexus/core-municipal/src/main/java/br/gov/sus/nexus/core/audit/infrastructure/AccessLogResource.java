package br.gov.sus.nexus.core.audit.infrastructure;

import br.gov.sus.nexus.core.audit.api.AccessLogEntry;
import br.gov.sus.nexus.core.audit.api.AccessLogService;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.OffsetDateTime;

/** {@code GET /api/v1/audit/access} — trilha de acessos (DPO/auditor). */
@Path("/api/v1/audit/access")
@Produces(MediaType.APPLICATION_JSON)
public class AccessLogResource {

  @Inject AccessLogService accessLogService;

  @GET
  @RolesAllowed({Roles.DPO, Roles.AUDITOR, Roles.ADMIN_MUNICIPAL})
  public Page<AccessLogEntry> list(
      @QueryParam("citizen_id") String citizenId,
      @QueryParam("actor_id") String actorId,
      @QueryParam("from") OffsetDateTime from,
      @QueryParam("to") OffsetDateTime to,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return accessLogService.list(citizenId, actorId, from, to, cursor, limit);
  }
}
