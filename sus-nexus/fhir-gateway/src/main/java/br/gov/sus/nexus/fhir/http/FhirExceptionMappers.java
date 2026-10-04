package br.gov.sus.nexus.fhir.http;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.interaction.FhirException;
import br.gov.sus.nexus.fhir.interaction.OperationOutcomes;
import br.gov.sus.nexus.fhir.security.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Converte toda exceção em {@code OperationOutcome} com status FHIR adequado. */
public class FhirExceptionMappers {

  private static final Logger LOG = Logger.getLogger(FhirExceptionMappers.class);

  @Inject FhirCodec codec;
  @Inject RequestContext context;

  @ServerExceptionMapper
  public Response fhir(FhirException e) {
    return respond(e.status(), e.outcome());
  }

  @ServerExceptionMapper
  public Response notFound(NotFoundException e) {
    return respond(
        404,
        OperationOutcomes.single(
            IssueSeverity.ERROR, IssueType.NOTFOUND, "Caminho não encontrado", null));
  }

  @ServerExceptionMapper
  public Response notAllowed(NotAllowedException e) {
    return respond(
        405,
        OperationOutcomes.single(
            IssueSeverity.ERROR, IssueType.NOTSUPPORTED, "Método HTTP não suportado", null));
  }

  @ServerExceptionMapper
  public Response unsupportedMediaType(NotSupportedException e) {
    return respond(
        415,
        OperationOutcomes.single(
            IssueSeverity.ERROR,
            IssueType.NOTSUPPORTED,
            "Content-Type não suportado; use application/fhir+json",
            null));
  }

  @ServerExceptionMapper
  public Response web(WebApplicationException e) {
    int status = e.getResponse() != null ? e.getResponse().getStatus() : 500;
    return respond(
        status,
        OperationOutcomes.single(
            IssueSeverity.ERROR,
            status >= 500 ? IssueType.EXCEPTION : IssueType.PROCESSING,
            "Erro HTTP " + status,
            null));
  }

  @ServerExceptionMapper
  public Response generic(Exception e) {
    LOG.errorf(
        e,
        "Erro interno (correlation=%s): %s",
        context.correlationId(),
        e.getClass().getSimpleName());
    return respond(
        500,
        OperationOutcomes.single(
            IssueSeverity.FATAL,
            IssueType.EXCEPTION,
            "Erro interno; correlation-id " + context.correlationId(),
            null));
  }

  private Response respond(int status, OperationOutcome outcome) {
    return Response.status(status)
        .type(FhirConstants.MEDIA_TYPE_FHIR_JSON)
        .entity(codec.encode(outcome))
        .build();
  }
}
