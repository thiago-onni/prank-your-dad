package br.gov.sus.nexus.core.scheduling.infrastructure;

import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.Roles;
import br.gov.sus.nexus.core.scheduling.api.AppointmentDto;
import br.gov.sus.nexus.core.scheduling.api.AppointmentDuplicateDto;
import br.gov.sus.nexus.core.scheduling.api.AppointmentRegistration;
import br.gov.sus.nexus.core.scheduling.api.AppointmentResult;
import br.gov.sus.nexus.core.scheduling.api.AppointmentService;
import br.gov.sus.nexus.core.scheduling.api.AppointmentStatus;
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
import java.time.OffsetDateTime;

/** {@code /api/v1/appointments} — agenda consolidada da rede e duplicidades. */
@Path("/api/v1/appointments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppointmentResource {

  @Inject AppointmentService service;

  @GET
  @RolesAllowed({
    Roles.PROFISSIONAL_APS,
    Roles.ACS,
    Roles.REGULADOR,
    Roles.AGENDADOR,
    Roles.GESTOR,
    Roles.OPERADOR_INTEGRACAO,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  public Page<AppointmentDto> list(
      @QueryParam("citizen_id") String citizenId,
      @QueryParam("cnes") String cnes,
      @QueryParam("status") String status,
      @QueryParam("from") OffsetDateTime from,
      @QueryParam("to") OffsetDateTime to,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    AppointmentStatus st =
        status == null || status.isBlank() ? null : AppointmentStatus.fromWire(status);
    return service.list(citizenId, cnes, st, from, to, cursor, limit);
  }

  @POST
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.AGENDADOR, Roles.ADMIN_MUNICIPAL})
  public Response register(@Valid AppointmentRegistration registration) {
    AppointmentResult result = service.register(registration);
    return Response.status(result.created() ? 201 : 200).entity(result.appointment()).build();
  }

  @GET
  @Path("/duplicates")
  @RolesAllowed({Roles.AGENDADOR, Roles.GESTOR, Roles.REGULADOR, Roles.ADMIN_MUNICIPAL})
  public Page<AppointmentDuplicateDto> duplicates(
      @QueryParam("window_hours") Integer windowHours,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return service.listDuplicates(windowHours, cursor, limit);
  }

  @GET
  @Path("/{appointmentId}")
  @RolesAllowed({
    Roles.PROFISSIONAL_APS,
    Roles.ACS,
    Roles.REGULADOR,
    Roles.AGENDADOR,
    Roles.GESTOR,
    Roles.OPERADOR_INTEGRACAO,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  public AppointmentDto get(@PathParam("appointmentId") String appointmentId) {
    return service.get(appointmentId);
  }
}
