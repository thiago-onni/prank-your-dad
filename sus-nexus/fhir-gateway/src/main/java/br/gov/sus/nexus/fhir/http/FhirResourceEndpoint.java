package br.gov.sus.nexus.fhir.http;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.capability.CapabilityRegistry;
import br.gov.sus.nexus.fhir.capability.CapabilityStatementBuilder;
import br.gov.sus.nexus.fhir.capability.Interaction;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.interaction.BinaryInteractionService;
import br.gov.sus.nexus.fhir.interaction.BundleProcessor;
import br.gov.sus.nexus.fhir.interaction.FhirException;
import br.gov.sus.nexus.fhir.interaction.FhirInteractionService;
import br.gov.sus.nexus.fhir.interaction.InteractionResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.hl7.fhir.r4.model.Resource;

/**
 * Endpoints RESTful FHIR R4 ({@code /fhir/r4}). Toda rota declara sua interação/operação via {@link
 * FhirRoute}; o teste de consistência garante que rotas e registro coincidem.
 *
 * <p>FHIR-3: {@code POST [base]} (Bundle batch/transaction), {@code GET _history} e {@code GET
 * [type]/_history}, {@code DELETE} lógico, {@code GET Patient/[id]/$everything} e {@code Binary}
 * (conteúdo no object storage; leitura devolve o recurso FHIR ou o conteúdo bruto conforme {@code
 * Accept}).
 */
@Path("/fhir/r4")
@Produces(FhirConstants.MEDIA_TYPE_FHIR_JSON)
@Consumes({FhirConstants.MEDIA_TYPE_FHIR_JSON, "application/json"})
public class FhirResourceEndpoint {

  static final String TYPE = "{type: [A-Z][A-Za-z]+}";
  static final String ID = "{id: [A-Za-z0-9\\-\\.]{1,64}}";
  static final String VID = "{vid: [0-9]{1,9}}";

  /** {@code $validate}, aceitando o cifrão literal ou codificado (%24). */
  static final String VALIDATE = "{op: (\\$|%24)validate}";

  /** {@code $everything} de instância. */
  static final String EVERYTHING = "{op: (\\$|%24)everything}";

  @Inject FhirInteractionService service;
  @Inject BinaryInteractionService binaries;
  @Inject BundleProcessor bundles;
  @Inject CapabilityRegistry registry;
  @Inject CapabilityStatementBuilder capabilityStatement;
  @Inject FhirCodec codec;

  @GET
  @Path("metadata")
  public Response metadata(@Context UriInfo uriInfo) {
    return ok(capabilityStatement.build(baseUrl(uriInfo)));
  }

  // ---- sistema
  // -------------------------------------------------------------------------------------

  /** {@code POST [base]}: Bundle {@code batch} ou {@code transaction}. */
  @POST
  @FhirRoute(system = {"transaction", "batch"})
  public Response bundle(@Context UriInfo uriInfo, String body) {
    return ok(bundles.process(body, baseUrl(uriInfo)));
  }

  @GET
  @Path("_history")
  @FhirRoute(system = "history-system")
  public Response historySystem(@Context UriInfo uriInfo) {
    Map<String, List<String>> params = new TreeMap<>(uriInfo.getQueryParameters());
    return toResponse(service.historyList(null, params, baseUrl(uriInfo)));
  }

  @POST
  @Path(VALIDATE)
  @FhirRoute(operation = "validate")
  public Response validate(String body) {
    return ok(service.validate(body, null));
  }

  // ---- tipo
  // ----------------------------------------------------------------------------------------

  @POST
  @Path(TYPE + "/" + VALIDATE)
  @FhirRoute(operation = "validate")
  public Response validateType(@PathParam("type") String type, String body) {
    return ok(service.validate(body, type));
  }

  @GET
  @Path(TYPE)
  @FhirRoute(Interaction.SEARCH_TYPE)
  public Response search(@PathParam("type") String type, @Context UriInfo uriInfo) {
    Map<String, List<String>> params = new TreeMap<>(uriInfo.getQueryParameters());
    InteractionResult result =
        service.search(type, params, baseUrl(uriInfo), uriInfo.getRequestUri().toString());
    return toResponse(result);
  }

  @POST
  @Path(TYPE)
  @FhirRoute(Interaction.CREATE)
  public Response create(@PathParam("type") String type, @Context UriInfo uriInfo, String body) {
    if ("Binary".equals(type)) {
      return toResponse(binaries.create(body, baseUrl(uriInfo)));
    }
    return toResponse(service.create(type, body, baseUrl(uriInfo)));
  }

  @GET
  @Path(TYPE + "/_history")
  @FhirRoute(Interaction.HISTORY_TYPE)
  public Response historyType(@PathParam("type") String type, @Context UriInfo uriInfo) {
    Map<String, List<String>> params = new TreeMap<>(uriInfo.getQueryParameters());
    return toResponse(service.historyList(type, params, baseUrl(uriInfo)));
  }

  // ---- instância
  // -----------------------------------------------------------------------------------

  @GET
  @Path(TYPE + "/" + ID)
  @Produces({FhirConstants.MEDIA_TYPE_FHIR_JSON, "application/json", "*/*"})
  @FhirRoute(Interaction.READ)
  public Response read(
      @PathParam("type") String type,
      @PathParam("id") String id,
      @HeaderParam("If-None-Match") String ifNoneMatch,
      @HeaderParam("Accept") String accept) {
    if ("Binary".equals(type)) {
      return binary(binaries.read(id), accept);
    }
    return toResponse(service.read(type, id, ifNoneMatch));
  }

  @PUT
  @Path(TYPE + "/" + ID)
  @FhirRoute(Interaction.UPDATE)
  public Response update(
      @PathParam("type") String type,
      @PathParam("id") String id,
      @HeaderParam("If-Match") String ifMatch,
      @Context UriInfo uriInfo,
      String body) {
    return toResponse(service.update(type, id, body, ifMatch, baseUrl(uriInfo)));
  }

  /** {@code PATCH} com JSON Patch; {@code If-Match} opcional (padrão: versão lida). */
  @PATCH
  @Path(TYPE + "/" + ID)
  @Consumes({FhirConstants.MEDIA_TYPE_JSON_PATCH, "application/json"})
  @FhirRoute(Interaction.PATCH)
  public Response patch(
      @PathParam("type") String type,
      @PathParam("id") String id,
      @HeaderParam("If-Match") String ifMatch,
      @Context UriInfo uriInfo,
      String body) {
    return toResponse(service.patch(type, id, body, ifMatch, baseUrl(uriInfo)));
  }

  @DELETE
  @Path(TYPE + "/" + ID)
  @FhirRoute(Interaction.DELETE)
  public Response delete(
      @PathParam("type") String type, @PathParam("id") String id, @Context UriInfo uriInfo) {
    return toResponse(service.delete(type, id, baseUrl(uriInfo)));
  }

  @GET
  @Path(TYPE + "/" + ID + "/_history")
  @FhirRoute(Interaction.HISTORY_INSTANCE)
  public Response history(
      @PathParam("type") String type, @PathParam("id") String id, @Context UriInfo uriInfo) {
    return toResponse(service.history(type, id, baseUrl(uriInfo)));
  }

  @GET
  @Path(TYPE + "/" + ID + "/_history/" + VID)
  @FhirRoute(Interaction.VREAD)
  public Response vread(
      @PathParam("type") String type, @PathParam("id") String id, @PathParam("vid") String vid) {
    return toResponse(service.vread(type, id, vid));
  }

  /** {@code GET Patient/[id]/$everything} (único tipo que registra a operação). */
  @GET
  @Path(TYPE + "/" + ID + "/" + EVERYTHING)
  @FhirRoute(operation = "everything")
  public Response everything(
      @PathParam("type") String type, @PathParam("id") String id, @Context UriInfo uriInfo) {
    boolean supported =
        registry.resource(type).map(c -> c.supportsOperation("everything")).orElse(false);
    if (!supported) {
      throw FhirException.notSupported("Operação $everything não suportada para " + type);
    }
    Map<String, List<String>> params = new TreeMap<>(uriInfo.getQueryParameters());
    return toResponse(service.everything(id, params, baseUrl(uriInfo)));
  }

  // ---- helpers
  // -------------------------------------------------------------------------------------

  static String baseUrl(UriInfo uriInfo) {
    String base = uriInfo.getBaseUri().toString();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + "/fhir/r4";
  }

  private Response ok(Resource resource) {
    return Response.ok(codec.encode(resource), FhirConstants.MEDIA_TYPE_FHIR_JSON).build();
  }

  /**
   * Leitura de Binary: com {@code Accept} FHIR/JSON devolve o recurso ({@code data} em base64);
   * caso contrário devolve o conteúdo bruto com o {@code contentType} armazenado.
   */
  private Response binary(BinaryInteractionService.BinaryRead read, String accept) {
    boolean asResource =
        accept == null
            || accept.isBlank()
            || accept.contains("json")
            || accept.contains("*/*") && !accept.contains(read.contentType());
    Response.ResponseBuilder b = Response.ok();
    if (asResource) {
      b.entity(codec.encode(read.resource())).type(FhirConstants.MEDIA_TYPE_FHIR_JSON);
    } else {
      b.entity(read.content()).type(read.contentType());
    }
    b.header("ETag", read.etag());
    b.lastModified(Date.from(read.lastModified()));
    return b.build();
  }

  private Response toResponse(InteractionResult result) {
    Response.ResponseBuilder b = Response.status(result.status());
    if (result.body() != null) {
      b.entity(codec.encode(result.body())).type(FhirConstants.MEDIA_TYPE_FHIR_JSON);
    }
    if (result.etag() != null) {
      b.header("ETag", result.etag());
    }
    if (result.lastModified() != null) {
      b.lastModified(Date.from(result.lastModified()));
    }
    if (result.location() != null) {
      b.header("Location", result.location());
    }
    return b.build();
  }
}
