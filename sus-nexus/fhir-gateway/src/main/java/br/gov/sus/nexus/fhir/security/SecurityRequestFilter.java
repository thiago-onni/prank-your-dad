package br.gov.sus.nexus.fhir.security;

import br.gov.sus.nexus.fhir.FhirConstants;
import br.gov.sus.nexus.fhir.codec.FhirCodec;
import br.gov.sus.nexus.fhir.config.FhirGatewayConfig;
import br.gov.sus.nexus.fhir.interaction.OperationOutcomes;
import io.smallrye.common.annotation.Identifier;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.jboss.logging.Logger;

/**
 * Resolve identidade e contexto para rotas {@code /fhir} e {@code /internal}. A configuração é
 * injetada via {@code Instance} porque providers JAX-RS são instanciados no static-init do Quarkus,
 * antes do registro das {@code @ConfigMapping}. Requisições sem identidade recebem 401 com {@code
 * OperationOutcome}; {@code /fhir/r4/metadata} é público.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class SecurityRequestFilter implements ContainerRequestFilter {

  private static final Logger LOG = Logger.getLogger(SecurityRequestFilter.class);

  @Inject
  @Identifier("test-headers")
  IdentityResolver headerResolver;

  @Inject
  @Identifier("oidc")
  IdentityResolver oidcResolver;

  @Inject Instance<FhirGatewayConfig> config;
  @Inject RequestContext requestContext;
  @Inject FhirCodec codec;

  @Override
  public void filter(ContainerRequestContext request) {
    String path = request.getUriInfo().getPath();
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    boolean protectedPath = path.startsWith("fhir/") || path.startsWith("internal/");
    if (!protectedPath) {
      return;
    }
    String correlation = request.getHeaderString(FhirConstants.HEADER_CORRELATION_ID);
    requestContext.setCorrelationId(
        correlation == null || correlation.isBlank() ? UUID.randomUUID().toString() : correlation);
    requestContext.setPurposeOfUse(request.getHeaderString(FhirConstants.HEADER_PURPOSE_OF_USE));

    IdentityResolver resolver =
        "test-headers".equals(config.get().security().mode()) ? headerResolver : oidcResolver;
    Identity identity = resolver.resolve(request);
    requestContext.setIdentity(identity);

    boolean publicPath = path.equals("fhir/r4/metadata");
    if (!identity.isAuthenticated() && !publicPath) {
      LOG.debugf(
          "Requisição não autenticada em %s (correlation=%s)",
          path, requestContext.correlationId());
      request.abortWith(
          Response.status(401)
              .type(FhirConstants.MEDIA_TYPE_FHIR_JSON)
              .entity(
                  codec.encode(
                      OperationOutcomes.single(
                          IssueSeverity.ERROR, IssueType.LOGIN, "Autenticação requerida", null)))
              .build());
    }
  }
}
