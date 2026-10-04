package br.gov.sus.nexus.connectors.sdk.core;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Endpoints de operação de integração do core ({@code tags: integration} do OpenAPI) usados pelos
 * conectores: ledger espelho, heartbeat e reconciliação. Mesmo {@code configKey} e cabeçalhos do
 * {@link CoreApi}. Use pela fachada {@link CoreIntegrationMirror} (melhor esforço, sem PII).
 */
@Path("/api/v1/integration")
@RegisterRestClient(configKey = "core")
@RegisterClientHeaders(CoreHeadersFactory.class)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface CoreIntegrationApi {

  /** {@code recordIntegrationMessage}: upsert por id, sem payload. */
  @POST
  @Path("/messages")
  Response recordIntegrationMessage(
      @HeaderParam(CoreApi.CORRELATION_ID) String correlationId, Map<String, Object> body);

  /** {@code connectorHeartbeat}. */
  @POST
  @Path("/connectors/{connectorId}/heartbeat")
  Response heartbeat(@PathParam("connectorId") String connectorId, Map<String, Object> body);

  /** {@code recordReconciliation}. */
  @POST
  @Path("/reconciliation")
  Response recordReconciliation(Map<String, Object> body);
}
