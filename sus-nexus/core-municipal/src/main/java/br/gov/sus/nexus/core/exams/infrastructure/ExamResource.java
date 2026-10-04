package br.gov.sus.nexus.core.exams.infrastructure;

import br.gov.sus.nexus.core.audit.api.AuditedAccess;
import br.gov.sus.nexus.core.exams.api.ExamDocumentLink;
import br.gov.sus.nexus.core.exams.api.ExamIssue;
import br.gov.sus.nexus.core.exams.api.ExamOrderDto;
import br.gov.sus.nexus.core.exams.api.ExamOrderRegistration;
import br.gov.sus.nexus.core.exams.api.ExamOrderResult;
import br.gov.sus.nexus.core.exams.api.ExamOrderStatus;
import br.gov.sus.nexus.core.exams.api.ExamResultRegistration;
import br.gov.sus.nexus.core.exams.api.ExamService;
import br.gov.sus.nexus.core.exams.api.ExamStatusChange;
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

/** {@code /api/v1/exams/orders} — ciclo do exame, resultados e referência segura ao laudo. */
@Path("/api/v1/exams/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ExamResource {

  @Inject ExamService service;

  @GET
  @RolesAllowed({
    Roles.PROFISSIONAL_APS,
    Roles.PROFISSIONAL_HOSPITALAR,
    Roles.REGULADOR,
    Roles.AGENDADOR,
    Roles.GESTOR,
    Roles.OPERADOR_INTEGRACAO,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  @AuditedAccess(resourceType = "exam_order", action = "search")
  public Page<ExamOrderDto> list(
      @QueryParam("citizen_id") String citizenId,
      @QueryParam("status") String status,
      @QueryParam("issue") String issue,
      @QueryParam("requesting_cnes") String requestingCnes,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return service.list(
        citizenId,
        status == null || status.isBlank() ? null : ExamOrderStatus.fromWire(status),
        issue == null || issue.isBlank() ? null : ExamIssue.fromWire(issue),
        requestingCnes,
        cursor,
        limit);
  }

  @POST
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.PROFISSIONAL_APS, Roles.ADMIN_MUNICIPAL})
  public Response register(@Valid ExamOrderRegistration registration) {
    ExamOrderResult result = service.register(registration);
    return Response.status(result.created() ? 201 : 200).entity(result.order()).build();
  }

  @GET
  @Path("/{orderId}")
  @RolesAllowed({
    Roles.PROFISSIONAL_APS,
    Roles.PROFISSIONAL_HOSPITALAR,
    Roles.REGULADOR,
    Roles.AGENDADOR,
    Roles.GESTOR,
    Roles.OPERADOR_INTEGRACAO,
    Roles.AGENTE_IA,
    Roles.ADMIN_MUNICIPAL
  })
  @AuditedAccess(resourceType = "exam_order", action = "read")
  public ExamOrderDto get(@PathParam("orderId") String orderId) {
    return service.get(orderId);
  }

  @POST
  @Path("/{orderId}/status")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.PROFISSIONAL_APS, Roles.ADMIN_MUNICIPAL})
  public ExamOrderDto status(@PathParam("orderId") String orderId, @Valid ExamStatusChange change) {
    return service.changeStatus(orderId, change);
  }

  @POST
  @Path("/{orderId}/results")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.ADMIN_MUNICIPAL})
  public Response result(
      @PathParam("orderId") String orderId, @Valid ExamResultRegistration result) {
    return Response.status(201).entity(service.registerResult(orderId, result)).build();
  }

  /** Mesma operação para conectores (LIS/RIS) que só conhecem o registro de origem do pedido. */
  @POST
  @Path("/by-source/{system}/{sourceRecordId}/results")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.ADMIN_MUNICIPAL})
  public Response resultBySource(
      @PathParam("system") String system,
      @PathParam("sourceRecordId") String sourceRecordId,
      @Valid ExamResultRegistration result) {
    return Response.status(201)
        .entity(service.registerResultBySource(system, sourceRecordId, result))
        .build();
  }

  @GET
  @Path("/{orderId}/results/{resultId}/document")
  @RolesAllowed({
    Roles.PROFISSIONAL_APS,
    Roles.PROFISSIONAL_HOSPITALAR,
    Roles.REGULADOR,
    Roles.ADMIN_MUNICIPAL
  })
  @AuditedAccess(resourceType = "exam_result_document", action = "read")
  public ExamDocumentLink document(
      @PathParam("orderId") String orderId, @PathParam("resultId") String resultId) {
    return service.documentLink(orderId, resultId);
  }
}
