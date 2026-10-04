package br.gov.sus.nexus.core.regulation.infrastructure;

import br.gov.sus.nexus.core.audit.api.AuditedAccess;
import br.gov.sus.nexus.core.platform.ingestion.UpsertResult;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.Roles;
import br.gov.sus.nexus.core.regulation.api.ProviderCapacityDto;
import br.gov.sus.nexus.core.regulation.api.ProviderCapacityUpsert;
import br.gov.sus.nexus.core.regulation.api.RegulationIssueCreate;
import br.gov.sus.nexus.core.regulation.api.RegulationPriority;
import br.gov.sus.nexus.core.regulation.api.RegulationQuery;
import br.gov.sus.nexus.core.regulation.api.RegulationQueueItemDto;
import br.gov.sus.nexus.core.regulation.api.RegulationRequestDto;
import br.gov.sus.nexus.core.regulation.api.RegulationRequestRegistration;
import br.gov.sus.nexus.core.regulation.api.RegulationResult;
import br.gov.sus.nexus.core.regulation.api.RegulationService;
import br.gov.sus.nexus.core.regulation.api.RegulationStatus;
import br.gov.sus.nexus.core.regulation.api.RegulationStatusChange;
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
import java.util.Map;

/**
 * {@code /api/v1/regulation/*} — fila regulatória espelhada, pendências, capacidade, indicadores.
 */
@Path("/api/v1/regulation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RegulationResource {

  @Inject RegulationService service;

  @GET
  @Path("/requests")
  @RolesAllowed({
    Roles.REGULADOR,
    Roles.GESTOR,
    Roles.AGENDADOR,
    Roles.PROFISSIONAL_APS,
    Roles.OPERADOR_INTEGRACAO,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  @AuditedAccess(resourceType = "regulation_queue", action = "search")
  public Page<RegulationRequestDto> list(
      @QueryParam("citizen_id") String citizenId,
      @QueryParam("status") String status,
      @QueryParam("priority") String priority,
      @QueryParam("service_code") String serviceCode,
      @QueryParam("specialty") String specialty,
      @QueryParam("requesting_cnes") String requestingCnes,
      @QueryParam("provider_cnes") String providerCnes,
      @QueryParam("territory") String territory,
      @QueryParam("issue") String issue,
      @QueryParam("sort") String sort,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return service.list(
        new RegulationQuery(
            citizenId,
            status == null || status.isBlank() ? null : RegulationStatus.fromWire(status),
            priority == null || priority.isBlank() ? null : RegulationPriority.fromWire(priority),
            serviceCode,
            specialty,
            requestingCnes,
            providerCnes,
            territory,
            issue,
            sort,
            cursor,
            limit));
  }

  @POST
  @Path("/requests")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.REGULADOR, Roles.ADMIN_MUNICIPAL})
  public Response register(@Valid RegulationRequestRegistration registration) {
    RegulationResult result = service.register(registration);
    return Response.status(result.created() ? 201 : 200).entity(result.request()).build();
  }

  @GET
  @Path("/requests/{requestId}")
  @RolesAllowed({
    Roles.REGULADOR,
    Roles.GESTOR,
    Roles.AGENDADOR,
    Roles.PROFISSIONAL_APS,
    Roles.OPERADOR_INTEGRACAO,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  @AuditedAccess(resourceType = "regulation_request", action = "read")
  public RegulationRequestDto get(@PathParam("requestId") String requestId) {
    return service.get(requestId);
  }

  @POST
  @Path("/requests/{requestId}/status")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.REGULADOR, Roles.ADMIN_MUNICIPAL})
  public RegulationRequestDto status(
      @PathParam("requestId") String requestId, @Valid RegulationStatusChange change) {
    return service.changeStatus(requestId, change);
  }

  /** Mesma operação para conectores que só conhecem o registro de origem. */
  @POST
  @Path("/requests/by-source/{system}/{sourceRecordId}/status")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.REGULADOR, Roles.ADMIN_MUNICIPAL})
  public RegulationRequestDto statusBySource(
      @PathParam("system") String system,
      @PathParam("sourceRecordId") String sourceRecordId,
      @Valid RegulationStatusChange change) {
    return service.changeStatusBySource(system, sourceRecordId, change);
  }

  @POST
  @Path("/requests/{requestId}/issues")
  @RolesAllowed({Roles.REGULADOR, Roles.AGENTE_IA, Roles.ADMIN_MUNICIPAL})
  public Response addIssue(
      @PathParam("requestId") String requestId, @Valid RegulationIssueCreate issue) {
    return Response.status(201).entity(service.addIssue(requestId, issue)).build();
  }

  @GET
  @Path("/capacity")
  @RolesAllowed({
    Roles.REGULADOR,
    Roles.GESTOR,
    Roles.AGENDADOR,
    Roles.OPERADOR_INTEGRACAO,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  public Page<ProviderCapacityDto> capacity(
      @QueryParam("provider_cnes") String providerCnes,
      @QueryParam("service_code") String serviceCode,
      @QueryParam("competence") String competence,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return service.listCapacity(providerCnes, serviceCode, competence, cursor, limit);
  }

  @POST
  @Path("/capacity")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.REGULADOR, Roles.GESTOR, Roles.ADMIN_MUNICIPAL})
  public UpsertResult upsertCapacity(@Valid ProviderCapacityUpsert upsert) {
    return service.upsertCapacity(upsert);
  }

  @GET
  @Path("/queues/summary")
  @RolesAllowed({Roles.GESTOR, Roles.REGULADOR, Roles.ADMIN_MUNICIPAL})
  public Map<String, List<RegulationQueueItemDto>> queueSummary(
      @QueryParam("group_by") String groupBy) {
    return Map.of("items", service.queueSummary(groupBy));
  }
}
