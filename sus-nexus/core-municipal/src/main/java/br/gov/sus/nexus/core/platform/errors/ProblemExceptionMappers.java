package br.gov.sus.nexus.core.platform.errors;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Mapeia exceções para RFC 9457 ({@code application/problem+json}). Nunca expõe PII. */
public class ProblemExceptionMappers {

  private static final Logger LOG = Logger.getLogger(ProblemExceptionMappers.class);

  @ServerExceptionMapper
  public RestResponse<ProblemDetails> problem(ProblemException e, UriInfo uriInfo) {
    return build(e.status(), e.title(), e.detail(), e.type(), e.errors(), uriInfo);
  }

  @ServerExceptionMapper
  public RestResponse<ProblemDetails> constraint(ConstraintViolationException e, UriInfo uriInfo) {
    List<ProblemException.FieldError> errors =
        e.getConstraintViolations().stream()
            .map(
                v ->
                    new ProblemException.FieldError(
                        lastSegment(v.getPropertyPath().toString()), v.getMessage()))
            .toList();
    return build(
        400,
        "Requisição inválida",
        "Falha de validação",
        "urn:sus-nexus:problem:bad-request",
        errors,
        uriInfo);
  }

  @ServerExceptionMapper
  public RestResponse<ProblemDetails> unauthorized(UnauthorizedException e, UriInfo uriInfo) {
    return build(
        401,
        "Não autenticado",
        "Credenciais ausentes ou inválidas",
        "urn:sus-nexus:problem:unauthorized",
        List.of(),
        uriInfo);
  }

  @ServerExceptionMapper
  public RestResponse<ProblemDetails> authFailed(AuthenticationFailedException e, UriInfo uriInfo) {
    return build(
        401,
        "Não autenticado",
        "Falha de autenticação",
        "urn:sus-nexus:problem:unauthorized",
        List.of(),
        uriInfo);
  }

  @ServerExceptionMapper
  public RestResponse<ProblemDetails> forbidden(ForbiddenException e, UriInfo uriInfo) {
    return build(
        403,
        "Acesso negado",
        "Papel insuficiente para a operação",
        "urn:sus-nexus:problem:forbidden",
        List.of(),
        uriInfo);
  }

  @ServerExceptionMapper
  public RestResponse<ProblemDetails> webApp(WebApplicationException e, UriInfo uriInfo) {
    int status = e.getResponse() != null ? e.getResponse().getStatus() : 500;
    String title = status == 404 ? "Recurso não encontrado" : "Erro na requisição";
    return build(
        status, title, e.getMessage(), "urn:sus-nexus:problem:http-" + status, List.of(), uriInfo);
  }

  @ServerExceptionMapper
  public RestResponse<ProblemDetails> generic(Throwable e, UriInfo uriInfo) {
    LOG.error("Erro não tratado", e);
    return build(
        500,
        "Erro interno",
        "Erro interno; consulte correlation_id",
        "urn:sus-nexus:problem:internal",
        List.of(),
        uriInfo);
  }

  private static RestResponse<ProblemDetails> build(
      int status,
      String title,
      String detail,
      String type,
      List<ProblemException.FieldError> errors,
      UriInfo uriInfo) {
    Object corr = MDC.get("correlation_id");
    ProblemDetails body =
        new ProblemDetails(
            type,
            title,
            status,
            detail,
            uriInfo != null ? uriInfo.getPath() : null,
            corr != null ? corr.toString() : null,
            errors == null || errors.isEmpty() ? null : errors);
    return RestResponse.ResponseBuilder.<ProblemDetails>create(status)
        .entity(body)
        .header("Content-Type", ProblemDetails.MEDIA_TYPE)
        .build();
  }

  private static String lastSegment(String path) {
    int i = path.lastIndexOf('.');
    return i >= 0 ? path.substring(i + 1) : path;
  }
}
