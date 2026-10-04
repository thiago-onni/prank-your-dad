package br.gov.sus.nexus.fhir.http;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.audit.ProvenanceFactory.ProjectionSource;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.interaction.FhirException;
import br.gov.sus.nexus.fhir.mapping.CanonicalAppointment;
import br.gov.sus.nexus.fhir.mapping.CanonicalCareGap;
import br.gov.sus.nexus.fhir.mapping.CanonicalCarePlan;
import br.gov.sus.nexus.fhir.mapping.CanonicalCitizen;
import br.gov.sus.nexus.fhir.mapping.CanonicalEncounter;
import br.gov.sus.nexus.fhir.mapping.CanonicalExamOrder;
import br.gov.sus.nexus.fhir.mapping.CanonicalExamResult;
import br.gov.sus.nexus.fhir.mapping.CanonicalHealthUnit;
import br.gov.sus.nexus.fhir.mapping.CanonicalHospitalEpisode;
import br.gov.sus.nexus.fhir.mapping.CanonicalRegulationRequest;
import br.gov.sus.nexus.fhir.mapping.CanonicalTask;
import br.gov.sus.nexus.fhir.mapping.ProjectionService;
import br.gov.sus.nexus.fhir.mapping.ProjectionService.ProjectionResult;
import br.gov.sus.nexus.fhir.security.Identity;
import br.gov.sus.nexus.fhir.security.Permission;
import br.gov.sus.nexus.fhir.security.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Canal interno de projeção (core → FHIR). Protegido por escopo de sistema com escrita ({@code
 * system/*.write} ou {@code system/<Tipo>.write}). Recebe o canônico completo (valores em claro)
 * vindo do core; idempotente por conteúdo e com {@code Provenance}. Em produção o mesmo serviço é
 * acionado pelo consumidor Kafka ({@code projection/}); este canal permanece para reprocessamento e
 * carga inicial.
 *
 * <p>FHIR-3: {@code exam-result} (ExamOrder com {@code results[]} → DiagnosticReport + Observations
 * + DocumentReference), {@code hospital-episode} (→ Encounter), {@code care-plan} (→ CarePlan) e
 * {@code care-gap} (→ Task).
 */
@Path("/internal/projections")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(FhirConstants.MEDIA_TYPE_FHIR_JSON)
public class InternalProjectionEndpoint {

  @Inject ProjectionService projections;
  @Inject RequestContext context;
  @Inject FhirCodec codec;

  @POST
  @Path("citizen")
  public Response citizen(
      CanonicalCitizen citizen,
      @HeaderParam("X-Source-System") String sourceSystem,
      @HeaderParam("X-Source-Record-Id") String sourceRecordId,
      @Context UriInfo uriInfo) {
    String tenant = requireSystemWrite("Patient");
    if (citizen == null || citizen.id() == null) {
      throw FhirException.invalid("Canônico de cidadão sem id");
    }
    ProjectionResult result =
        projections.projectCitizen(
            tenant, citizen, source(sourceSystem, sourceRecordId, citizen.updatedAt()));
    return respond(result, "Patient", uriInfo);
  }

  @POST
  @Path("health-unit")
  public Response healthUnit(
      CanonicalHealthUnit unit,
      @HeaderParam("X-Source-System") String sourceSystem,
      @HeaderParam("X-Source-Record-Id") String sourceRecordId,
      @Context UriInfo uriInfo) {
    String tenant = requireSystemWrite("Organization");
    if (unit == null || unit.id() == null) {
      throw FhirException.invalid("Canônico de unidade sem id");
    }
    ProjectionResult result =
        projections.projectHealthUnit(tenant, unit, source(sourceSystem, sourceRecordId, null));
    return respond(result, "Organization", uriInfo);
  }

  @POST
  @Path("appointment")
  public Response appointment(
      CanonicalAppointment appointment,
      @HeaderParam("X-Source-System") String sourceSystem,
      @HeaderParam("X-Source-Record-Id") String sourceRecordId,
      @Context UriInfo uriInfo) {
    String tenant = requireSystemWrite("Appointment");
    if (appointment == null || appointment.id() == null || appointment.citizenId() == null) {
      throw FhirException.invalid("Canônico de agendamento sem id ou citizen_id");
    }
    ProjectionResult result =
        projections.projectAppointment(
            tenant,
            appointment,
            source(
                firstNonBlank(sourceSystem, appointment.sourceSystem()),
                firstNonBlank(sourceRecordId, appointment.sourceRecordId()),
                appointment.scheduledStart()));
    return respond(result, "Appointment", uriInfo);
  }

  @POST
  @Path("task")
  public Response task(
      CanonicalTask task,
      @HeaderParam("X-Source-System") String sourceSystem,
      @HeaderParam("X-Source-Record-Id") String sourceRecordId,
      @Context UriInfo uriInfo) {
    String tenant = requireSystemWrite("Task");
    if (task == null || task.id() == null || task.taskType() == null) {
      throw FhirException.invalid("Canônico de tarefa sem id ou task_type");
    }
    ProjectionResult result =
        projections.projectTask(
            tenant, task, source(sourceSystem, sourceRecordId, task.updatedAt()));
    return respond(result, "Task", uriInfo);
  }

  @POST
  @Path("regulation-request")
  public Response regulationRequest(
      CanonicalRegulationRequest request,
      @HeaderParam("X-Source-System") String sourceSystem,
      @HeaderParam("X-Source-Record-Id") String sourceRecordId,
      @Context UriInfo uriInfo) {
    String tenant = requireSystemWrite("ServiceRequest");
    if (request == null || request.id() == null || request.citizenId() == null) {
      throw FhirException.invalid("Canônico de regulação sem id ou citizen_id");
    }
    ProjectionResult result =
        projections.projectRegulationRequest(
            tenant,
            request,
            source(
                firstNonBlank(sourceSystem, request.sourceSystem()),
                firstNonBlank(sourceRecordId, request.sourceRecordId()),
                request.requestedAt()));
    return respond(result, "ServiceRequest", uriInfo);
  }

  @POST
  @Path("exam-order")
  public Response examOrder(
      CanonicalExamOrder order,
      @HeaderParam("X-Source-System") String sourceSystem,
      @HeaderParam("X-Source-Record-Id") String sourceRecordId,
      @Context UriInfo uriInfo) {
    String tenant = requireSystemWrite("ServiceRequest");
    if (order == null || order.id() == null || order.citizenId() == null) {
      throw FhirException.invalid("Canônico de pedido de exame sem id ou citizen_id");
    }
    ProjectionResult result =
        projections.projectExamOrder(
            tenant,
            order,
            source(
                firstNonBlank(sourceSystem, order.sourceSystem()),
                firstNonBlank(sourceRecordId, order.sourceRecordId()),
                order.requestedAt()));
    return respond(result, "ServiceRequest", uriInfo);
  }

  @POST
  @Path("encounter")
  public Response encounter(
      CanonicalEncounter encounter,
      @HeaderParam("X-Source-System") String sourceSystem,
      @HeaderParam("X-Source-Record-Id") String sourceRecordId,
      @Context UriInfo uriInfo) {
    String tenant = requireSystemWrite("Encounter");
    if (encounter == null || encounter.encounterId() == null || encounter.citizenId() == null) {
      throw FhirException.invalid("Atendimento sem encounter_id ou citizen_id");
    }
    ProjectionResult result =
        projections.projectEncounter(
            tenant,
            encounter,
            source(
                firstNonBlank(sourceSystem, encounter.sourceSystem()),
                firstNonBlank(sourceRecordId, encounter.sourceRecordId()),
                encounter.occurredAt() != null ? encounter.occurredAt() : encounter.start()));
    return respond(result, "Encounter", uriInfo);
  }

  // ---- FHIR-3 ---------------------------------------------------------------------------------

  /**
   * Projeta os resultados de um pedido de exame ({@code results[]}); com {@code result_id} apenas o
   * resultado indicado. Responde com o {@code DiagnosticReport} do último resultado projetado (201
   * quando foi criado) e {@code X-Provenance-Location} da sua Provenance quando houve mudança.
   */
  @POST
  @Path("exam-result")
  public Response examResult(
      CanonicalExamOrder order,
      @QueryParam("result_id") String resultId,
      @HeaderParam("X-Source-System") String sourceSystem,
      @HeaderParam("X-Source-Record-Id") String sourceRecordId,
      @Context UriInfo uriInfo) {
    String tenant = requireSystemWrite("DiagnosticReport");
    if (order == null || order.id() == null || order.citizenId() == null) {
      throw FhirException.invalid("Canônico de pedido de exame sem id ou citizen_id");
    }
    List<CanonicalExamResult> results =
        order.results() == null
            ? List.of()
            : order.results().stream()
                .filter(r -> r != null && r.id() != null)
                .filter(r -> resultId == null || resultId.isBlank() || resultId.equals(r.id()))
                .toList();
    if (results.isEmpty()) {
      throw FhirException.invalid(
          "Pedido de exame sem resultado a projetar (results[] vazio ou result_id inexistente)");
    }
    ProjectionService.ExamResultProjection last = null;
    for (CanonicalExamResult result : results) {
      last =
          projections.projectExamResult(
              tenant,
              order,
              result,
              source(
                  firstNonBlank(
                      sourceSystem, firstNonBlank(result.sourceSystem(), order.sourceSystem())),
                  firstNonBlank(
                      sourceRecordId,
                      firstNonBlank(result.sourceRecordId(), order.sourceRecordId())),
                  result.reportedAt()));
    }
    return respond(last.report(), "DiagnosticReport", uriInfo);
  }

  @POST
  @Path("hospital-episode")
  public Response hospitalEpisode(
      CanonicalHospitalEpisode episode,
      @HeaderParam("X-Source-System") String sourceSystem,
      @HeaderParam("X-Source-Record-Id") String sourceRecordId,
      @Context UriInfo uriInfo) {
    String tenant = requireSystemWrite("Encounter");
    if (episode == null || episode.id() == null || episode.citizenId() == null) {
      throw FhirException.invalid("Canônico de episódio hospitalar sem id ou citizen_id");
    }
    ProjectionResult result =
        projections.projectHospitalEpisode(
            tenant,
            episode,
            source(
                firstNonBlank(sourceSystem, episode.sourceSystem()),
                firstNonBlank(sourceRecordId, episode.sourceRecordId()),
                episode.dischargedAt() != null ? episode.dischargedAt() : episode.admittedAt()));
    return respond(result, "Encounter", uriInfo);
  }

  @POST
  @Path("care-plan")
  public Response carePlan(
      CanonicalCarePlan plan,
      @HeaderParam("X-Source-System") String sourceSystem,
      @HeaderParam("X-Source-Record-Id") String sourceRecordId,
      @Context UriInfo uriInfo) {
    String tenant = requireSystemWrite("CarePlan");
    if (plan == null || plan.id() == null || plan.citizenId() == null) {
      throw FhirException.invalid("Canônico de plano de cuidado sem id ou citizen_id");
    }
    ProjectionResult result =
        projections.projectCarePlan(
            tenant,
            plan,
            source(
                sourceSystem,
                sourceRecordId,
                plan.updatedAt() != null ? plan.updatedAt() : plan.createdAt()));
    return respond(result, "CarePlan", uriInfo);
  }

  @POST
  @Path("care-gap")
  public Response careGap(
      CanonicalCareGap gap,
      @HeaderParam("X-Source-System") String sourceSystem,
      @HeaderParam("X-Source-Record-Id") String sourceRecordId,
      @Context UriInfo uriInfo) {
    String tenant = requireSystemWrite("Task");
    if (gap == null || gap.id() == null || gap.citizenId() == null) {
      throw FhirException.invalid("Canônico de lacuna de cuidado sem id ou citizen_id");
    }
    ProjectionResult result =
        projections.projectCareGap(
            tenant,
            gap,
            source(
                sourceSystem,
                sourceRecordId,
                gap.resolvedAt() != null ? gap.resolvedAt() : gap.detectedAt()));
    return respond(result, "Task", uriInfo);
  }

  private static String firstNonBlank(String a, String b) {
    return a != null && !a.isBlank() ? a : b;
  }

  private String requireSystemWrite(String type) {
    Identity identity = context.identity();
    if (!identity.isAuthenticated()) {
      throw FhirException.unauthorized("Autenticação requerida");
    }
    if (!identity.hasSystemScope(type, Permission.CREATE)
        || !identity.hasSystemScope(type, Permission.UPDATE)) {
      throw FhirException.forbidden("Projeção exige escopo system/*.write");
    }
    if (!identity.hasTenant()) {
      throw FhirException.forbidden("Tenant não identificado (X-Tenant-Id)");
    }
    return identity.tenantId();
  }

  private ProjectionSource source(String system, String recordId, Instant occurredAt) {
    return new ProjectionSource(
        system == null || system.isBlank() ? "core-municipal" : system,
        recordId,
        occurredAt,
        context.correlationId());
  }

  private Response respond(ProjectionResult result, String type, UriInfo uriInfo) {
    String base = FhirResourceEndpoint.baseUrl(uriInfo);
    Response.ResponseBuilder b =
        Response.status(result.created() ? 201 : 200)
            .type(FhirConstants.MEDIA_TYPE_FHIR_JSON)
            .entity(codec.encode(result.resource()))
            .header("ETag", result.stored().etag())
            .header(
                "Location",
                base
                    + "/"
                    + type
                    + "/"
                    + result.stored().id()
                    + "/_history/"
                    + result.stored().versionId())
            .lastModified(Date.from(result.stored().lastUpdated()));
    result
        .provenance()
        .ifPresent(
            p ->
                b.header(
                    "X-Provenance-Location", base + "/Provenance/" + p.getIdElement().getIdPart()));
    return b.build();
  }
}
