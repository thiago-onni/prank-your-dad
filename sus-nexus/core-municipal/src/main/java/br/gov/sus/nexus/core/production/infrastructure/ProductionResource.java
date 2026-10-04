package br.gov.sus.nexus.core.production.infrastructure;

import br.gov.sus.nexus.core.audit.api.AuditedAccess;
import br.gov.sus.nexus.core.platform.errors.DomainValidationException;
import br.gov.sus.nexus.core.platform.errors.NotFoundException;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.Roles;
import br.gov.sus.nexus.core.production.api.ProductionBatchApproval;
import br.gov.sus.nexus.core.production.api.ProductionBatchCreate;
import br.gov.sus.nexus.core.production.api.ProductionBatchDto;
import br.gov.sus.nexus.core.production.api.ProductionBatchExportRequest;
import br.gov.sus.nexus.core.production.api.ProductionCorrection;
import br.gov.sus.nexus.core.production.api.ProductionDeadlineDto;
import br.gov.sus.nexus.core.production.api.ProductionIssueDto;
import br.gov.sus.nexus.core.production.api.ProductionKind;
import br.gov.sus.nexus.core.production.api.ProductionOutcomeRegistration;
import br.gov.sus.nexus.core.production.api.ProductionOutcomeResult;
import br.gov.sus.nexus.core.production.api.ProductionRecordDto;
import br.gov.sus.nexus.core.production.api.ProductionRecordRegistration;
import br.gov.sus.nexus.core.production.api.ProductionRecordResult;
import br.gov.sus.nexus.core.production.api.ProductionRecordStatus;
import br.gov.sus.nexus.core.production.api.ProductionRuleVersionCreate;
import br.gov.sus.nexus.core.production.api.ProductionRuleVersionDto;
import br.gov.sus.nexus.core.production.api.ProductionService;
import br.gov.sus.nexus.core.production.api.ProductionSummary;
import br.gov.sus.nexus.core.production.application.ProductionAuthorization;
import br.gov.sus.nexus.core.production.application.ProductionAuthorization.Resource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/v1/production} — registros, pendências, lotes, retornos, painel e prazos
 * (PRO-001..010). Papéis: {@code auditor} (tudo), {@code gestor} (painel, prazos, leitura, aprovar
 * lote), {@code operador_integracao} (registros e retornos), {@code agente_ia} (somente leitura de
 * pendências — nunca correção, aprovação ou exportação). Além do {@code @RolesAllowed}, cada ação
 * consulta a {@link ProductionAuthorization} (OPA {@code policies/sus/production} com {@code
 * sus.authz.mode=opa}, fail-closed): tenant, finalidade {@code production_audit}, agente só lê
 * pendências e quatro olhos na aprovação do lote.
 */
@Path("/api/v1/production")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductionResource {

  @Inject ProductionService service;
  @Inject ProductionAuthorization authz;

  @GET
  @Path("/records")
  @RolesAllowed({Roles.AUDITOR, Roles.GESTOR, Roles.OPERADOR_INTEGRACAO, Roles.ADMIN_MUNICIPAL})
  @AuditedAccess(resourceType = "production_record", action = "search")
  public Page<ProductionRecordDto> list(
      @QueryParam("competence") String competence,
      @QueryParam("cnes") String cnes,
      @QueryParam("kind") String kind,
      @QueryParam("status") String status,
      @QueryParam("procedure_code") String procedureCode,
      @QueryParam("citizen_id") String citizenId,
      @QueryParam("batch_id") String batchId,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    authz.require(Resource.RECORD, "read", null);
    return service.list(
        competence,
        cnes,
        kind(kind),
        status == null || status.isBlank()
            ? null
            : parse("status", () -> ProductionRecordStatus.fromWire(status)),
        procedureCode,
        citizenId,
        batchId,
        cursor,
        limit);
  }

  @POST
  @Path("/records")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.AUDITOR})
  public Response register(@Valid @NotNull ProductionRecordRegistration registration) {
    authz.require(Resource.RECORD, "register_record", null);
    ProductionRecordResult result = service.register(registration);
    return Response.status(result.created() ? 201 : 200).entity(result.record()).build();
  }

  @GET
  @Path("/records/{recordId}")
  @RolesAllowed({Roles.AUDITOR, Roles.GESTOR, Roles.OPERADOR_INTEGRACAO, Roles.ADMIN_MUNICIPAL})
  @AuditedAccess(resourceType = "production_record", action = "read")
  public ProductionRecordDto get(@PathParam("recordId") String recordId) {
    authz.require(Resource.RECORD, "read", recordId);
    return service.get(recordId);
  }

  @GET
  @Path("/records/by-source/{system}/{sourceRecordId}")
  @RolesAllowed({Roles.AUDITOR, Roles.GESTOR, Roles.OPERADOR_INTEGRACAO, Roles.ADMIN_MUNICIPAL})
  @AuditedAccess(resourceType = "production_record", action = "read")
  public ProductionRecordDto bySource(
      @PathParam("system") String system, @PathParam("sourceRecordId") String sourceRecordId) {
    authz.require(Resource.RECORD, "read", null);
    return service
        .findBySource(system, sourceRecordId)
        .orElseThrow(
            () -> new NotFoundException("registro de produção", system + "/" + sourceRecordId));
  }

  /**
   * Correção humana com justificativa (PRO-006). Agente de IA → 403 (papel e verificação
   * explícita).
   */
  @POST
  @Path("/records/{recordId}/corrections")
  @RolesAllowed({Roles.AUDITOR})
  public ProductionRecordDto correct(
      @PathParam("recordId") String recordId, @Valid @NotNull ProductionCorrection correction) {
    authz.require(Resource.RECORD, "correct", recordId);
    return service.correct(recordId, correction);
  }

  @GET
  @Path("/issues")
  @RolesAllowed({Roles.AUDITOR, Roles.GESTOR, Roles.AGENTE_IA, Roles.ADMIN_MUNICIPAL})
  public Page<ProductionIssueDto> issues(
      @QueryParam("severity") String severity,
      @QueryParam("rule") String rule,
      @QueryParam("competence") String competence,
      @QueryParam("cnes") String cnes,
      @QueryParam("kind") String kind,
      @QueryParam("status") String status,
      @QueryParam("record_id") String recordId,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    authz.require(Resource.ISSUE, "read", null);
    return service.issues(
        severity, rule, competence, cnes, kind(kind), status, recordId, cursor, limit);
  }

  @GET
  @Path("/batches")
  @RolesAllowed({Roles.AUDITOR, Roles.GESTOR, Roles.ADMIN_MUNICIPAL})
  public Page<ProductionBatchDto> batches(
      @QueryParam("competence") String competence,
      @QueryParam("cnes") String cnes,
      @QueryParam("status") String status,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    authz.require(Resource.BATCH, "read", null);
    return service.listBatches(competence, cnes, status, cursor, limit);
  }

  @POST
  @Path("/batches")
  @RolesAllowed({Roles.AUDITOR})
  public Response createBatch(@Valid @NotNull ProductionBatchCreate create) {
    authz.require(Resource.BATCH, "create_batch", null);
    return Response.status(201).entity(service.createBatch(create)).build();
  }

  @GET
  @Path("/batches/{batchId}")
  @RolesAllowed({Roles.AUDITOR, Roles.GESTOR, Roles.ADMIN_MUNICIPAL})
  public ProductionBatchDto batch(@PathParam("batchId") String batchId) {
    authz.require(Resource.BATCH, "read", batchId);
    return service.getBatch(batchId);
  }

  /**
   * Aprovação humana obrigatória (PRO-010) com quatro olhos: quem gerou o lote não o aprova (403
   * {@code urn:sus-nexus:problem:four-eyes} — política e serviço).
   */
  @POST
  @Path("/batches/{batchId}/approve")
  @RolesAllowed({Roles.AUDITOR, Roles.GESTOR})
  public ProductionBatchDto approve(
      @PathParam("batchId") String batchId, @Valid @NotNull ProductionBatchApproval approval) {
    ProductionBatchDto current = service.getBatch(batchId);
    Map<String, Object> attributes = new HashMap<>();
    if (current.createdBy() != null) {
      attributes.put("created_by", current.createdBy());
    }
    authz.require(Resource.BATCH, "approve_batch", batchId, attributes);
    return service.approveBatch(batchId, approval);
  }

  @POST
  @Path("/batches/{batchId}/export")
  @RolesAllowed({Roles.AUDITOR})
  public ProductionBatchDto export(
      @PathParam("batchId") String batchId, @Valid ProductionBatchExportRequest request) {
    authz.require(Resource.BATCH, "export_batch", batchId);
    return service.exportBatch(batchId, request);
  }

  @POST
  @Path("/outcomes")
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.AUDITOR})
  public ProductionOutcomeResult outcome(@Valid @NotNull ProductionOutcomeRegistration outcome) {
    authz.require(Resource.OUTCOME, "register_outcome", null);
    return service.registerOutcome(outcome);
  }

  @GET
  @Path("/summary")
  @RolesAllowed({Roles.GESTOR, Roles.AUDITOR, Roles.ADMIN_MUNICIPAL})
  public ProductionSummary summary(
      @QueryParam("competence") String competence, @QueryParam("cnes") String cnes) {
    authz.require(Resource.SUMMARY, "read", null);
    return service.summary(competence, cnes);
  }

  @GET
  @Path("/deadlines")
  @RolesAllowed({Roles.AUDITOR, Roles.GESTOR, Roles.OPERADOR_INTEGRACAO, Roles.ADMIN_MUNICIPAL})
  public Map<String, List<ProductionDeadlineDto>> deadlines(
      @QueryParam("from") String from, @QueryParam("to") String to) {
    authz.require(Resource.DEADLINE, "read", null);
    return Map.of("items", service.deadlines(from, to));
  }

  /**
   * Nova versão da regra de pré-auditoria {@code production-validation} do tenant: jsonb validado e
   * casos de teste executados antes de ativar (422 se falharem). Gestor/admin_municipal.
   */
  @POST
  @Path("/rules")
  @RolesAllowed({Roles.GESTOR, Roles.ADMIN_MUNICIPAL})
  public Response createRuleVersion(@Valid @NotNull ProductionRuleVersionCreate create) {
    authz.require(Resource.RULE, "create_rule_version", null);
    ProductionRuleVersionDto created = service.createRuleVersion(create);
    return Response.status(201).entity(created).build();
  }

  private static ProductionKind kind(String kind) {
    return kind == null || kind.isBlank()
        ? null
        : parse("kind", () -> ProductionKind.fromWire(kind));
  }

  private static <T> T parse(String field, java.util.function.Supplier<T> s) {
    try {
      return s.get();
    } catch (IllegalArgumentException e) {
      throw DomainValidationException.field(field, "valor inválido");
    }
  }
}
