package br.gov.sus.nexus.fhir.directwrite;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.interaction.FhirException;
import br.gov.sus.nexus.fhir.mapping.CanonicalIds;
import br.gov.sus.nexus.fhir.persistence.FhirResourceRepository;
import br.gov.sus.nexus.fhir.persistence.TenantTransaction;
import br.gov.sus.nexus.fhir.projection.CoreMunicipalClient;
import br.gov.sus.nexus.fhir.security.Identity;
import br.gov.sus.nexus.fhir.security.Permission;
import br.gov.sus.nexus.fhir.security.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.StringType;
import org.jboss.logging.Logger;

/**
 * Escrita direta por parceiro externo (seção 6.2 do plano): {@code POST/PUT} com escopo de sistema
 * em {@code Patient} ({@code system/Patient.write}) ou {@code ServiceRequest} é convertido para o
 * canônico e entra no core pela mesma porta dos conectores ({@code POST /api/v1/citizens}, {@code
 * POST /api/v1/exams/orders}). O recurso FHIR só é gravado depois que o core responde (200/201/202)
 * e passa a carregar o id municipal resolvido como {@code identifier} e tag {@code direct-write}
 * (202 → também {@code pending-identity}). O core continua sendo o dono do dado; a projeção
 * posterior sobrescreve o recurso com o canônico.
 */
@ApplicationScoped
public class DirectWriteService {

  private static final Logger LOG = Logger.getLogger(DirectWriteService.class);

  @Inject @RestClient CoreMunicipalClient core;
  @Inject PatientToCitizenMapper patientMapper;
  @Inject ServiceRequestToExamOrderMapper examMapper;
  @Inject RequestContext context;
  @Inject TenantTransaction tx;
  @Inject FhirResourceRepository repository;
  @Inject ObjectMapper json;

  /** Recurso enriquecido, id FHIR resolvido pelo core e status a devolver (200/201/202). */
  public record Outcome(Resource resource, String id, int status) {}

  /** Só parceiros com escopo de sistema em Patient/ServiceRequest. */
  public boolean applies(String type, Identity identity) {
    return ("Patient".equals(type) || "ServiceRequest".equals(type))
        && identity.hasSystemScope(type, Permission.CREATE);
  }

  public Outcome register(String type, Resource resource, String tenant) {
    String correlation = context.correlationId();
    CitizenRegistration.Source source = source(resource);
    return switch (type) {
      case "Patient" -> registerPatient((Patient) resource, source, tenant, correlation);
      case "ServiceRequest" ->
          registerExamOrder((ServiceRequest) resource, source, tenant, correlation);
      default -> throw new IllegalArgumentException("Tipo sem escrita direta: " + type);
    };
  }

  private Outcome registerPatient(
      Patient patient, CitizenRegistration.Source source, String tenant, String correlation) {
    CitizenRegistration body = patientMapper.map(patient, source);
    CoreReply reply =
        call(
            () ->
                core.registerCitizen(
                    body, tenant, correlation, idempotencyKey("citizen", source.sourceRecordId())));
    String municipalId = reply.body().path("municipal_citizen_id").asText(null);
    if (municipalId == null || municipalId.isBlank()) {
      throw new FhirException(502, IssueType.EXCEPTION, "Core respondeu sem municipal_citizen_id");
    }
    String id = CanonicalIds.toFhirId(municipalId);
    Patient out = patient.copy();
    replaceIdentifier(out.getIdentifier(), FhirConstants.SYSTEM_MUNICIPAL_CITIZEN_ID, municipalId);
    out.setId(id);
    tag(out, FhirConstants.TAG_DIRECT_WRITE);
    if (reply.status() == 202) {
      tag(out, FhirConstants.TAG_PENDING_IDENTITY);
    }
    String classification = reply.body().path("classification").asText(null);
    if (classification != null) {
      Extension ext = out.addExtension().setUrl(FhirConstants.EXT_IDENTITY_RESOLUTION);
      ext.addExtension("classification", new StringType(classification));
      String mergeCase = reply.body().path("merge_case_id").asText(null);
      if (mergeCase != null && !mergeCase.isBlank()) {
        ext.addExtension("merge-case-id", new StringType(mergeCase));
      }
    }
    LOG.infof(
        "Escrita direta Patient → core (status=%d classification=%s correlation=%s)",
        reply.status(), classification, correlation);
    return new Outcome(out, id, reply.status());
  }

  private Outcome registerExamOrder(
      ServiceRequest sr, CitizenRegistration.Source source, String tenant, String correlation) {
    ExamOrderRegistration body = examMapper.map(sr, source, orgId -> cnesOf(tenant, orgId));
    CoreReply reply =
        call(
            () ->
                core.registerExamOrder(
                    body, tenant, correlation, idempotencyKey("exam", source.sourceRecordId())));
    String orderId = reply.body().path("id").asText(null);
    if (orderId == null || orderId.isBlank()) {
      throw new FhirException(502, IssueType.EXCEPTION, "Core respondeu sem id do pedido");
    }
    String id = CanonicalIds.toFhirId(orderId);
    ServiceRequest out = sr.copy();
    replaceIdentifier(out.getIdentifier(), FhirConstants.SYSTEM_MUNICIPAL_EXAM_ORDER_ID, orderId);
    out.setId(id);
    tag(out, FhirConstants.TAG_DIRECT_WRITE);
    LOG.infof(
        "Escrita direta ServiceRequest → core (status=%d correlation=%s)",
        reply.status(), correlation);
    return new Outcome(out, id, reply.status() == 202 ? 202 : reply.status());
  }

  private Optional<String> cnesOf(String tenant, String organizationId) {
    return tx.execute(tenant, c -> repository.findCurrent(c, "Organization", organizationId))
        .filter(s -> !s.deleted())
        .flatMap(
            s -> {
              try {
                JsonNode node = json.readTree(s.content());
                for (JsonNode idn : node.path("identifier")) {
                  if (FhirConstants.SYSTEM_CNES.equals(idn.path("system").asText(null))) {
                    return Optional.ofNullable(idn.path("value").asText(null));
                  }
                }
              } catch (java.io.IOException e) {
                return Optional.empty();
              }
              return Optional.empty();
            });
  }

  /**
   * Origem: sistema = parceiro autenticado; registro de origem = primeiro identifier do recurso.
   */
  private CitizenRegistration.Source source(Resource resource) {
    String recordId = null;
    if (resource instanceof DomainResource) {
      java.util.List<Identifier> ids =
          resource instanceof Patient p
              ? p.getIdentifier()
              : resource instanceof ServiceRequest s ? s.getIdentifier() : java.util.List.of();
      recordId =
          ids.stream()
              .filter(Identifier::hasValue)
              .filter(i -> !FhirConstants.SYSTEM_MUNICIPAL_CITIZEN_ID.equals(i.getSystem()))
              .map(Identifier::getValue)
              .findFirst()
              .orElse(null);
    }
    if (recordId == null) {
      recordId =
          resource.hasId() ? resource.getIdElement().getIdPart() : UUID.randomUUID().toString();
    }
    return new CitizenRegistration.Source(
        "FHIR", "fhir-gateway:" + context.identity().subject(), recordId, null, null);
  }

  private static String idempotencyKey(String kind, String recordId) {
    return kind + ":" + UUID.nameUUIDFromBytes((kind + "|" + recordId).getBytes());
  }

  private record CoreReply(int status, JsonNode body) {}

  /** Chama o core traduzindo falhas: 4xx → 422 (problem.title), 5xx/indisponível → 502. */
  private CoreReply call(Supplier<Response> request) {
    Response response;
    try {
      response = request.get();
    } catch (WebApplicationException e) {
      response = e.getResponse();
      if (response == null) {
        throw new FhirException(502, IssueType.EXCEPTION, "Core municipal indisponível");
      }
    } catch (RuntimeException e) {
      LOG.warnf(
          "Falha ao chamar o core (correlation=%s): %s",
          context.correlationId(), e.getClass().getSimpleName());
      throw new FhirException(502, IssueType.EXCEPTION, "Core municipal indisponível");
    }
    int status = response.getStatus();
    String text = response.hasEntity() ? response.readEntity(String.class) : "";
    JsonNode body;
    try {
      body = text == null || text.isBlank() ? json.createObjectNode() : json.readTree(text);
    } catch (java.io.IOException e) {
      body = json.createObjectNode();
    }
    if (status == 200 || status == 201 || status == 202) {
      return new CoreReply(status, body);
    }
    if (status >= 400 && status < 500) {
      String title = body.path("title").asText("Core rejeitou o registro");
      throw new FhirException(
          422,
          IssueType.BUSINESSRULE,
          "Core municipal recusou o registro (HTTP " + status + "): " + title);
    }
    throw new FhirException(502, IssueType.EXCEPTION, "Core municipal respondeu HTTP " + status);
  }

  private static void replaceIdentifier(
      java.util.List<Identifier> identifiers, String system, String value) {
    identifiers.removeIf(i -> system.equals(i.getSystem()));
    identifiers.add(
        new Identifier()
            .setUse(Identifier.IdentifierUse.OFFICIAL)
            .setSystem(system)
            .setValue(value));
  }

  private static void tag(Resource resource, String code) {
    boolean present =
        resource.getMeta().getTag().stream()
            .anyMatch(
                t ->
                    FhirConstants.CS_MUNICIPAL_TAG.equals(t.getSystem())
                        && code.equals(t.getCode()));
    if (!present) {
      resource
          .getMeta()
          .addTag(new Coding().setSystem(FhirConstants.CS_MUNICIPAL_TAG).setCode(code));
    }
  }
}
