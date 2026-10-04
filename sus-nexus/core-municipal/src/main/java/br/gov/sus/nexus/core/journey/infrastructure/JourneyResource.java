package br.gov.sus.nexus.core.journey.infrastructure;

import br.gov.sus.nexus.core.audit.api.AuditedAccess;
import br.gov.sus.nexus.core.journey.api.CitizenOperationalSummary;
import br.gov.sus.nexus.core.journey.api.JourneyService;
import br.gov.sus.nexus.core.journey.api.TimelineEventDto;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/** {@code /api/v1/citizens/{citizenId}/timeline} e {@code /summary} (JOR-*). */
@Path("/api/v1/citizens/{citizenId}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({
  Roles.PROFISSIONAL_APS,
  Roles.ACS,
  Roles.REGULADOR,
  Roles.AGENDADOR,
  Roles.PROFISSIONAL_HOSPITALAR,
  Roles.OPERADOR_INTEGRACAO,
  Roles.AGENTE_IA,
  Roles.ADMIN_MUNICIPAL
})
public class JourneyResource {

  @Inject JourneyService service;

  @GET
  @Path("/timeline")
  @AuditedAccess(resourceType = "timeline", action = "read")
  public Page<TimelineEventDto> timeline(
      @PathParam("citizenId") String citizenId,
      @QueryParam("from") OffsetDateTime from,
      @QueryParam("to") OffsetDateTime to,
      @QueryParam("domain") String domain,
      @QueryParam("cnes") String cnes,
      @QueryParam("status") String status,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    List<String> domains =
        domain == null || domain.isBlank()
            ? null
            : Arrays.stream(domain.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    return service.timeline(citizenId, from, to, domains, cnes, status, cursor, limit);
  }

  @GET
  @Path("/summary")
  @AuditedAccess(resourceType = "citizen_summary", action = "read")
  public CitizenOperationalSummary summary(@PathParam("citizenId") String citizenId) {
    return service.summary(citizenId);
  }
}
