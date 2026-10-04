package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.identity.api.MergeCaseDto;
import br.gov.sus.nexus.core.identity.api.MergeCaseService;
import br.gov.sus.nexus.core.identity.api.MergeCaseStatus;
import br.gov.sus.nexus.core.identity.api.Requests;
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

/** {@code /api/v1/mpi} — fila de revisão: casos, merge, reject e unmerge. */
@Path("/api/v1/mpi")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MpiResource {

  @Inject MergeCaseService service;

  @GET
  @Path("/cases")
  @RolesAllowed({Roles.GESTOR, Roles.PROFISSIONAL_APS, Roles.ADMIN_MUNICIPAL})
  public Page<MergeCaseDto> list(
      @QueryParam("status") String status,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    MergeCaseStatus st =
        status == null || status.isBlank() ? null : MergeCaseStatus.fromWire(status);
    return service.list(st, cursor, limit);
  }

  @GET
  @Path("/cases/{caseId}")
  @RolesAllowed({Roles.GESTOR, Roles.PROFISSIONAL_APS, Roles.ADMIN_MUNICIPAL})
  public MergeCaseDto get(@PathParam("caseId") String caseId) {
    return service.get(caseId);
  }

  @POST
  @Path("/cases/{caseId}/merge")
  @RolesAllowed({Roles.GESTOR, Roles.ADMIN_MUNICIPAL})
  public MergeCaseDto merge(@PathParam("caseId") String caseId, @Valid Requests.Merge request) {
    return service.merge(caseId, request);
  }

  @POST
  @Path("/cases/{caseId}/reject")
  @RolesAllowed({Roles.GESTOR, Roles.ADMIN_MUNICIPAL})
  public MergeCaseDto reject(@PathParam("caseId") String caseId, @Valid Requests.Reason request) {
    return service.reject(caseId, request);
  }

  @POST
  @Path("/merges/{mergeId}/unmerge")
  @RolesAllowed({Roles.GESTOR, Roles.ADMIN_MUNICIPAL})
  public MergeCaseDto unmerge(
      @PathParam("mergeId") String mergeId, @Valid Requests.Reason request) {
    return service.unmerge(mergeId, request);
  }
}
