package br.gov.sus.nexus.connectors.sdk.core;

import br.gov.sus.nexus.connectors.sdk.core.dto.AppointmentRegistration;
import br.gov.sus.nexus.connectors.sdk.core.dto.CitizenRegistration;
import br.gov.sus.nexus.connectors.sdk.core.dto.CodeUpsertBatch;
import br.gov.sus.nexus.connectors.sdk.core.dto.HealthUnitUpsertBatch;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Cliente MicroProfile REST da API de entrada do core ({@code
 * contracts/openapi/core-municipal.yaml}). Configuração: {@code quarkus.rest-client.core.url}.
 * Headers {@code Authorization} e {@code X-Tenant-Id} são injetados por {@link CoreHeadersFactory};
 * {@code Idempotency-Key} e {@code X-Correlation-Id} vêm por chamada.
 */
@Path("/api/v1")
@RegisterRestClient(configKey = "core")
@RegisterClientHeaders(CoreHeadersFactory.class)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface CoreApi {

  String IDEMPOTENCY_KEY = "Idempotency-Key";
  String CORRELATION_ID = "X-Correlation-Id";
  String TENANT_ID = "X-Tenant-Id";
  String PURPOSE = "X-Purpose-Of-Use";

  @POST
  @Path("/citizens")
  Response registerCitizen(
      @HeaderParam(IDEMPOTENCY_KEY) String idempotencyKey,
      @HeaderParam(CORRELATION_ID) String correlationId,
      CitizenRegistration body);

  @POST
  @Path("/appointments")
  Response registerAppointment(
      @HeaderParam(IDEMPOTENCY_KEY) String idempotencyKey,
      @HeaderParam(CORRELATION_ID) String correlationId,
      AppointmentRegistration body);

  @POST
  @Path("/reference/health-units/upsert")
  Response upsertHealthUnits(
      @HeaderParam(IDEMPOTENCY_KEY) String idempotencyKey,
      @HeaderParam(CORRELATION_ID) String correlationId,
      HealthUnitUpsertBatch body);

  @POST
  @Path("/terminology/{system}/codes/upsert")
  Response upsertCodes(
      @PathParam("system") String system,
      @HeaderParam(IDEMPOTENCY_KEY) String idempotencyKey,
      @HeaderParam(CORRELATION_ID) String correlationId,
      CodeUpsertBatch body);
}
