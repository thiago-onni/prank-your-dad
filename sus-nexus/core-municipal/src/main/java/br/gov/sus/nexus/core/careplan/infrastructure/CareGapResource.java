package br.gov.sus.nexus.core.careplan.infrastructure;

import br.gov.sus.nexus.core.audit.api.AuditedAccess;
import br.gov.sus.nexus.core.careplan.api.CareGapDto;
import br.gov.sus.nexus.core.careplan.api.CareGapKind;
import br.gov.sus.nexus.core.careplan.api.CareGapResolve;
import br.gov.sus.nexus.core.careplan.api.CarePlanService;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** {@code /api/v1/caregaps} — lista de busca ativa por UBS/eSF/microárea (CUI-003/004/006). */
@Path("/api/v1/caregaps")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CareGapResource {

  @Inject CarePlanService service;

  @GET
  @RolesAllowed({
    Roles.PROFISSIONAL_APS,
    Roles.ACS,
    Roles.GESTOR,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  @AuditedAccess(resourceType = "care_gap", action = "search")
  public Page<CareGapDto> list(
      @QueryParam("care_line") String careLine,
      @QueryParam("gap_kind") String gapKind,
      @QueryParam("cnes") String cnes,
      @QueryParam("team_ine") String teamIne,
      @QueryParam("microarea") String microarea,
      @QueryParam("status") String status,
      @QueryParam("min_days_overdue") Integer minDaysOverdue,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return service.listGaps(
        careLine,
        gapKind == null || gapKind.isBlank() ? null : CareGapKind.fromWire(gapKind),
        cnes,
        teamIne,
        microarea,
        status,
        minDaysOverdue,
        cursor,
        limit);
  }

  @POST
  @Path("/{careGapId}/resolve")
  @RolesAllowed({Roles.PROFISSIONAL_APS, Roles.ACS, Roles.GESTOR, Roles.ADMIN_MUNICIPAL})
  public CareGapDto resolve(
      @PathParam("careGapId") String careGapId, @Valid CareGapResolve resolve) {
    return service.resolveGap(careGapId, resolve);
  }
}
