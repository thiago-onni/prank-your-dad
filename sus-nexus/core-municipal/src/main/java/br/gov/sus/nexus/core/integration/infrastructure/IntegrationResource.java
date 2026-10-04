package br.gov.sus.nexus.core.integration.infrastructure;

import br.gov.sus.nexus.core.integration.api.ConnectorHeartbeat;
import br.gov.sus.nexus.core.integration.api.ConnectorStatusDto;
import br.gov.sus.nexus.core.integration.api.DeadLetterDto;
import br.gov.sus.nexus.core.integration.api.IntegrationMessageDto;
import br.gov.sus.nexus.core.integration.api.IntegrationMessageStatus;
import br.gov.sus.nexus.core.integration.api.IntegrationMessageWrite;
import br.gov.sus.nexus.core.integration.api.IntegrationService;
import br.gov.sus.nexus.core.integration.api.ReconciliationEntryDto;
import br.gov.sus.nexus.core.platform.pagination.Page;
import br.gov.sus.nexus.core.platform.security.Roles;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
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
import java.util.List;
import java.util.Map;

/** {@code /api/v1/integration} — conectores, mensagens, reprocessamento, DLQ e reconciliação. */
@Path("/api/v1/integration")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({Roles.OPERADOR_INTEGRACAO, Roles.ADMIN_MUNICIPAL})
public class IntegrationResource {

  /** Corpo opcional do reprocessamento. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReprocessRequest(String reason) {}

  @Inject IntegrationService service;

  @GET
  @Path("/connectors")
  public List<ConnectorStatusDto> connectors() {
    return service.listConnectors();
  }

  /** Heartbeat/registro do conector (cliente técnico). */
  @POST
  @Path("/connectors/{connectorId}/heartbeat")
  public ConnectorStatusDto heartbeat(
      @PathParam("connectorId") String connectorId, @Valid ConnectorHeartbeat heartbeat) {
    return service.heartbeat(connectorId, heartbeat);
  }

  @GET
  @Path("/messages")
  public Page<IntegrationMessageDto> messages(
      @QueryParam("connector_id") String connectorId,
      @QueryParam("status") String status,
      @QueryParam("from") OffsetDateTime from,
      @QueryParam("to") OffsetDateTime to,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    IntegrationMessageStatus st =
        status == null || status.isBlank() ? null : IntegrationMessageStatus.fromWire(status);
    return service.listMessages(connectorId, st, from, to, cursor, limit);
  }

  /** Escrita no ledger espelho pelos conectores (upsert por id). */
  @POST
  @Path("/messages")
  public Response record(@Valid IntegrationMessageWrite write) {
    return Response.ok(service.recordMessage(write)).build();
  }

  @GET
  @Path("/messages/{messageId}")
  public IntegrationMessageDto message(@PathParam("messageId") String messageId) {
    return service.getMessage(messageId);
  }

  @POST
  @Path("/messages/{messageId}/reprocess")
  public Response reprocess(@PathParam("messageId") String messageId, ReprocessRequest body) {
    String eventId = service.reprocess(messageId, body == null ? null : body.reason());
    return Response.accepted(Map.of("message_id", messageId, "event_id", eventId)).build();
  }

  @GET
  @Path("/dlq")
  public Page<DeadLetterDto> dlq(
      @QueryParam("connector_id") String connectorId,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return service.listDeadLetters(connectorId, cursor, limit);
  }

  @GET
  @Path("/reconciliation")
  public Page<ReconciliationEntryDto> reconciliation(
      @QueryParam("connector_id") String connectorId,
      @QueryParam("cursor") String cursor,
      @QueryParam("limit") Integer limit) {
    return service.listReconciliation(connectorId, cursor, limit);
  }

  /** Relatório de reconciliação enviado pelo conector (cliente técnico). */
  @POST
  @Path("/reconciliation")
  public Response recordReconciliation(@Valid ReconciliationEntryDto entry) {
    return Response.status(201).entity(service.recordReconciliation(entry)).build();
  }
}
