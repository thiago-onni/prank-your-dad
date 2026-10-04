package br.gov.sus.nexus.core.careplan.infrastructure;

import br.gov.sus.nexus.core.careplan.api.CarePlanService;
import br.gov.sus.nexus.core.careplan.api.ProtocolCreate;
import br.gov.sus.nexus.core.careplan.api.ProtocolDto;
import br.gov.sus.nexus.core.careplan.api.ProtocolTransition;
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
import java.util.List;

/** {@code /api/v1/protocols} — protocolos configuráveis e versionados (CUI-009). */
@Path("/api/v1/protocols")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProtocolResource {

  @Inject CarePlanService service;

  @GET
  @RolesAllowed({
    Roles.PROFISSIONAL_APS,
    Roles.ACS,
    Roles.PROFISSIONAL_HOSPITALAR,
    Roles.GESTOR,
    Roles.AGENTE_IA,
    Roles.OPERADOR_INTEGRACAO,
    Roles.ADMIN_MUNICIPAL
  })
  public List<ProtocolDto> list(
      @QueryParam("care_line") String careLine, @QueryParam("status") String status) {
    return service.listProtocols(careLine, status);
  }

  @POST
  @RolesAllowed({Roles.GESTOR, Roles.PROFISSIONAL_APS, Roles.ADMIN_MUNICIPAL})
  public Response create(@Valid ProtocolCreate create) {
    return Response.status(201).entity(service.createProtocolVersion(create)).build();
  }

  /** Aprovação verificada no serviço: exige casos de teste e papel gestor/admin_municipal. */
  @POST
  @Path("/{protocolId}/versions/{version}/transition")
  @RolesAllowed({Roles.GESTOR, Roles.PROFISSIONAL_APS, Roles.ADMIN_MUNICIPAL})
  public ProtocolDto transition(
      @PathParam("protocolId") String protocolId,
      @PathParam("version") String version,
      @Valid ProtocolTransition transition) {
    return service.transitionProtocol(protocolId, version, transition);
  }
}
