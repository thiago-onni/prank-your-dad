package br.gov.sus.nexus.core.careplan.infrastructure;

import br.gov.sus.nexus.core.audit.api.AuditedAccess;
import br.gov.sus.nexus.core.careplan.api.CarePlanClose;
import br.gov.sus.nexus.core.careplan.api.CarePlanCreate;
import br.gov.sus.nexus.core.careplan.api.CarePlanDto;
import br.gov.sus.nexus.core.careplan.api.CarePlanItemUpdate;
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
import jakarta.ws.rs.core.Response;

/** {@code /api/v1/careplans} — planos de cuidado (CUI-001/002). */
@Path("/api/v1/careplans")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CarePlanResource {

  @Inject CarePlanService service;

  @GET
  @RolesAllowed({
    Roles.PROFISSIONAL_APS,
    Roles.ACS,
    Roles.PROFISSIONAL_HOSPITALAR,
    Roles.GESTOR,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  @AuditedAccess(resourceType = "care_plan", action = "search")
  public Page<CarePlanDto> list(
      @QueryParam("citizen_id") String citizenId,
      @QueryParam("care_line") String careLine,
      @QueryParam("status") String status,
      @QueryParam("team_ine") String teamIne,
      @QueryParam("cnes") String cnes,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return service.list(citizenId, careLine, status, teamIne, cnes, cursor, limit);
  }

  @POST
  @RolesAllowed({Roles.PROFISSIONAL_APS, Roles.GESTOR, Roles.ADMIN_MUNICIPAL})
  public Response create(@Valid CarePlanCreate create) {
    return Response.status(201).entity(service.create(create)).build();
  }

  @GET
  @Path("/{carePlanId}")
  @RolesAllowed({
    Roles.PROFISSIONAL_APS,
    Roles.ACS,
    Roles.PROFISSIONAL_HOSPITALAR,
    Roles.GESTOR,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  @AuditedAccess(resourceType = "care_plan", action = "read")
  public CarePlanDto get(@PathParam("carePlanId") String carePlanId) {
    return service.get(carePlanId);
  }

  @POST
  @Path("/{carePlanId}/items/{itemId}")
  @RolesAllowed({Roles.PROFISSIONAL_APS, Roles.ACS, Roles.ADMIN_MUNICIPAL})
  public CarePlanDto updateItem(
      @PathParam("carePlanId") String carePlanId,
      @PathParam("itemId") String itemId,
      @Valid CarePlanItemUpdate update) {
    return service.updateItem(carePlanId, itemId, update);
  }

  @POST
  @Path("/{carePlanId}/close")
  @RolesAllowed({Roles.PROFISSIONAL_APS, Roles.GESTOR, Roles.ADMIN_MUNICIPAL})
  public CarePlanDto close(@PathParam("carePlanId") String carePlanId, @Valid CarePlanClose close) {
    return service.close(carePlanId, close);
  }
}
