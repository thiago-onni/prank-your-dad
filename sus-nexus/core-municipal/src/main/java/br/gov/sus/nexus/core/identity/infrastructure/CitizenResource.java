package br.gov.sus.nexus.core.identity.infrastructure;

import br.gov.sus.nexus.core.audit.api.AuditedAccess;
import br.gov.sus.nexus.core.identity.api.CitizenDetail;
import br.gov.sus.nexus.core.identity.api.CitizenRegistration;
import br.gov.sus.nexus.core.identity.api.CitizenService;
import br.gov.sus.nexus.core.identity.api.CitizenSummary;
import br.gov.sus.nexus.core.identity.api.IdentityResolution;
import br.gov.sus.nexus.core.identity.api.RegistrationState;
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
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;

/** {@code /api/v1/citizens} — busca, registro (porta única), detalhe e reveal. */
@Path("/api/v1/citizens")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CitizenResource {

  @Inject CitizenService service;

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
  @AuditedAccess(resourceType = "citizen_search", action = "search")
  public Page<CitizenSummary> search(
      @QueryParam("q") String q,
      @QueryParam("identifier") String identifier,
      @QueryParam("birthdate") LocalDate birthdate,
      @QueryParam("registration_state") String registrationState,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    RegistrationState state =
        registrationState == null || registrationState.isBlank()
            ? null
            : RegistrationState.fromWire(registrationState);
    return service.search(q, identifier, birthdate, state, cursor, limit);
  }

  @POST
  @RolesAllowed({
    Roles.OPERADOR_INTEGRACAO,
    Roles.PROFISSIONAL_APS,
    Roles.ACS,
    Roles.ADMIN_MUNICIPAL
  })
  public Response register(@Valid CitizenRegistration registration) {
    IdentityResolution result = service.register(registration);
    return Response.status(result.httpStatus()).entity(result).build();
  }

  @GET
  @Path("/{citizenId}")
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
  @AuditedAccess(resourceType = "citizen", action = "read")
  public Response get(@PathParam("citizenId") String citizenId) {
    CitizenDetail detail = service.get(citizenId);
    return Response.ok(detail).header("ETag", "\"" + detail.version() + "\"").build();
  }

  @POST
  @Path("/{citizenId}/identifiers/{identifierId}/reveal")
  @RolesAllowed({Roles.PROFISSIONAL_APS, Roles.REGULADOR, Roles.ADMIN_MUNICIPAL})
  public Requests.Revealed reveal(
      @PathParam("citizenId") String citizenId,
      @PathParam("identifierId") String identifierId,
      @Valid Requests.Reveal request) {
    return service.reveal(citizenId, identifierId, request);
  }
}
