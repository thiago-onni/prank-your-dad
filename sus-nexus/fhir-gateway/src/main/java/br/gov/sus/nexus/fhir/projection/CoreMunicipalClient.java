package br.gov.sus.nexus.fhir.projection;

import br.gov.sus.nexus.fhir.directwrite.CitizenRegistration;
import br.gov.sus.nexus.fhir.directwrite.ExamOrderRegistration;
import br.gov.sus.nexus.fhir.mapping.CanonicalAppointment;
import br.gov.sus.nexus.fhir.mapping.CanonicalCarePlan;
import br.gov.sus.nexus.fhir.mapping.CanonicalExamOrder;
import br.gov.sus.nexus.fhir.mapping.CanonicalHospitalEpisode;
import br.gov.sus.nexus.fhir.mapping.CanonicalRegulationRequest;
import br.gov.sus.nexus.fhir.mapping.CanonicalTask;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
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
 * Leitura do canônico completo no core municipal ({@code contracts/openapi/core-municipal.yaml}) e
 * porta de entrada da escrita direta (FHIR-3, seção 6.2). Autenticação client-credentials
 * injetada por {@link CoreClientHeadersFactory}.
 */
@RegisterRestClient(configKey = "core-municipal")
@RegisterClientHeaders(CoreClientHeadersFactory.class)
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public interface CoreMunicipalClient {

  @GET
  @Path("/appointments/{id}")
  CanonicalAppointment appointment(
      @PathParam("id") String id,
      @HeaderParam("X-Tenant-Id") String tenant,
      @HeaderParam("X-Correlation-Id") String correlationId);

  @GET
  @Path("/tasks/{id}")
  CanonicalTask task(
      @PathParam("id") String id,
      @HeaderParam("X-Tenant-Id") String tenant,
      @HeaderParam("X-Correlation-Id") String correlationId);

  @GET
  @Path("/regulation/requests/{id}")
  CanonicalRegulationRequest regulationRequest(
      @PathParam("id") String id,
      @HeaderParam("X-Tenant-Id") String tenant,
      @HeaderParam("X-Correlation-Id") String correlationId);

  @GET
  @Path("/exams/orders/{id}")
  CanonicalExamOrder examOrder(
      @PathParam("id") String id,
      @HeaderParam("X-Tenant-Id") String tenant,
      @HeaderParam("X-Correlation-Id") String correlationId);

  // ---- FHIR-3 ---------------------------------------------------------------------------------

  @GET
  @Path("/hospital/episodes/{id}")
  CanonicalHospitalEpisode hospitalEpisode(
      @PathParam("id") String id,
      @HeaderParam("X-Tenant-Id") String tenant,
      @HeaderParam("X-Correlation-Id") String correlationId);

  @GET
  @Path("/careplans/{id}")
  CanonicalCarePlan carePlan(
      @PathParam("id") String id,
      @HeaderParam("X-Tenant-Id") String tenant,
      @HeaderParam("X-Correlation-Id") String correlationId);

  /** Escrita direta: registro de cidadão (resolve identidade; 200/201/202). */
  @POST
  @Path("/citizens")
  @Consumes(MediaType.APPLICATION_JSON)
  Response registerCitizen(
      CitizenRegistration body,
      @HeaderParam("X-Tenant-Id") String tenant,
      @HeaderParam("X-Correlation-Id") String correlationId,
      @HeaderParam("Idempotency-Key") String idempotencyKey);

  /** Escrita direta: registro de pedido de exame (200/201). */
  @POST
  @Path("/exams/orders")
  @Consumes(MediaType.APPLICATION_JSON)
  Response registerExamOrder(
      ExamOrderRegistration body,
      @HeaderParam("X-Tenant-Id") String tenant,
      @HeaderParam("X-Correlation-Id") String correlationId,
      @HeaderParam("Idempotency-Key") String idempotencyKey);
}
