package br.gov.sus.nexus.core.hospital.infrastructure;

import br.gov.sus.nexus.core.audit.api.AuditedAccess;
import br.gov.sus.nexus.core.hospital.api.CounterReferralRegistration;
import br.gov.sus.nexus.core.hospital.api.DischargeFollowup;
import br.gov.sus.nexus.core.hospital.api.DischargeRegistration;
import br.gov.sus.nexus.core.hospital.api.HospitalEpisodeDto;
import br.gov.sus.nexus.core.hospital.api.HospitalEpisodeResult;
import br.gov.sus.nexus.core.hospital.api.HospitalEpisodeStatus;
import br.gov.sus.nexus.core.hospital.api.HospitalMovementRegistration;
import br.gov.sus.nexus.core.hospital.api.HospitalService;
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
import java.time.OffsetDateTime;

/** {@code /api/v1/hospital/episodes} — ADT, alta, contrarreferência e contato pós-alta. */
@Path("/api/v1/hospital/episodes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class HospitalResource {

  @Inject HospitalService service;

  @GET
  @RolesAllowed({
    Roles.PROFISSIONAL_APS,
    Roles.ACS,
    Roles.PROFISSIONAL_HOSPITALAR,
    Roles.REGULADOR,
    Roles.GESTOR,
    Roles.OPERADOR_INTEGRACAO,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  @AuditedAccess(resourceType = "hospital_episode", action = "search")
  public Page<HospitalEpisodeDto> list(
      @QueryParam("citizen_id") String citizenId,
      @QueryParam("hospital_cnes") String hospitalCnes,
      @QueryParam("status") String status,
      @QueryParam("discharged_from") OffsetDateTime dischargedFrom,
      @QueryParam("discharged_to") OffsetDateTime dischargedTo,
      @QueryParam("reference_cnes") String referenceCnes,
      @QueryParam("followup_status") String followupStatus,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return service.list(
        citizenId,
        hospitalCnes,
        status == null || status.isBlank() ? null : HospitalEpisodeStatus.fromWire(status),
        dischargedFrom,
        dischargedTo,
        referenceCnes,
        followupStatus,
        cursor,
        limit);
  }

  @POST
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.PROFISSIONAL_HOSPITALAR, Roles.ADMIN_MUNICIPAL})
  public Response register(@Valid HospitalMovementRegistration registration) {
    HospitalEpisodeResult result = service.register(registration);
    return Response.status(result.created() ? 201 : 200).entity(result.episode()).build();
  }

  @GET
  @Path("/{episodeId}")
  @RolesAllowed({
    Roles.PROFISSIONAL_APS,
    Roles.ACS,
    Roles.PROFISSIONAL_HOSPITALAR,
    Roles.REGULADOR,
    Roles.GESTOR,
    Roles.OPERADOR_INTEGRACAO,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  @AuditedAccess(resourceType = "hospital_episode", action = "read")
  public HospitalEpisodeDto get(@PathParam("episodeId") String episodeId) {
    return service.get(episodeId);
  }

  @POST
  @Path("/{episodeId}/discharge")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.PROFISSIONAL_HOSPITALAR, Roles.ADMIN_MUNICIPAL})
  public HospitalEpisodeDto discharge(
      @PathParam("episodeId") String episodeId, @Valid DischargeRegistration discharge) {
    return service.discharge(episodeId, discharge);
  }

  /** Mesma operação para conectores (HIS) que só conhecem o registro de origem do episódio. */
  @POST
  @Path("/by-source/{system}/{sourceRecordId}/discharge")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.PROFISSIONAL_HOSPITALAR, Roles.ADMIN_MUNICIPAL})
  public HospitalEpisodeDto dischargeBySource(
      @PathParam("system") String system,
      @PathParam("sourceRecordId") String sourceRecordId,
      @Valid DischargeRegistration discharge) {
    return service.dischargeBySource(system, sourceRecordId, discharge);
  }

  @POST
  @Path("/{episodeId}/counter-referral")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.PROFISSIONAL_HOSPITALAR, Roles.ADMIN_MUNICIPAL})
  public Response counterReferral(
      @PathParam("episodeId") String episodeId, @Valid CounterReferralRegistration registration) {
    return Response.status(201).entity(service.counterReferral(episodeId, registration)).build();
  }

  @POST
  @Path("/{episodeId}/followup")
  @RolesAllowed({Roles.PROFISSIONAL_APS, Roles.ACS, Roles.GESTOR, Roles.ADMIN_MUNICIPAL})
  public HospitalEpisodeDto followup(
      @PathParam("episodeId") String episodeId, @Valid DischargeFollowup followup) {
    return service.followup(episodeId, followup);
  }
}
