package br.gov.sus.nexus.core.terminology.infrastructure;

import br.gov.sus.nexus.core.platform.ingestion.UpsertResult;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.Roles;
import br.gov.sus.nexus.core.terminology.api.CodeDto;
import br.gov.sus.nexus.core.terminology.api.CodeUpsertBatch;
import br.gov.sus.nexus.core.terminology.api.TerminologyService;
import io.quarkus.security.Authenticated;
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

/** {@code GET /api/v1/terminology/{system}/codes} e {@code POST .../codes/upsert}. */
@Path("/api/v1/terminology/{system}/codes")
@Produces(MediaType.APPLICATION_JSON)
public class TerminologyResource {

  @Inject TerminologyService service;

  @GET
  @Authenticated
  public Page<CodeDto> search(
      @PathParam("system") String system,
      @QueryParam("q") String q,
      @QueryParam("code") String code,
      @QueryParam("competence") String competence,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return service.search(system, q, code, competence, cursor, limit);
  }

  /** Upsert em lote por competência — porta dos conectores de terminologia; idempotente. */
  @POST
  @Path("/upsert")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.ADMIN_MUNICIPAL})
  public UpsertResult upsertBatch(
      @PathParam("system") String system, @Valid CodeUpsertBatch batch) {
    return service.upsertBatch(system, batch);
  }
}
