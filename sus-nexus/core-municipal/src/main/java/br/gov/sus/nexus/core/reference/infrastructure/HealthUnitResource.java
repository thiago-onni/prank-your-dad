package br.gov.sus.nexus.core.reference.infrastructure;

import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.Roles;
import br.gov.sus.nexus.core.reference.api.HealthUnitDto;
import br.gov.sus.nexus.core.reference.api.HealthUnitService;
import br.gov.sus.nexus.core.reference.api.HealthUnitUpsert;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/** {@code /api/v1/reference/health-units}. */
@Path("/api/v1/reference/health-units")
@Produces(MediaType.APPLICATION_JSON)
public class HealthUnitResource {

  @Inject HealthUnitService service;

  @GET
  @Authenticated
  public Page<HealthUnitDto> list(
      @QueryParam("q") String q,
      @QueryParam("cnes") String cnes,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return service.search(q, cnes, cursor, limit);
  }

  /** Upsert por CNES — porta usada pelos conectores (CNES/PEC). */
  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.ADMIN_MUNICIPAL})
  public HealthUnitDto upsert(HealthUnitUpsert command) {
    return service.upsert(command);
  }
}
