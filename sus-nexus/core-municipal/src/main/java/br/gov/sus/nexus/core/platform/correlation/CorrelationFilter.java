package br.gov.sus.nexus.core.platform.correlation;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import java.util.regex.Pattern;
import org.jboss.logging.MDC;

/** Resolve/gera o correlation id, coloca em MDC e devolve no header de resposta. */
@Provider
@PreMatching
@Priority(Priorities.HEADER_DECORATOR)
public class CorrelationFilter implements ContainerRequestFilter, ContainerResponseFilter {

  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

  @Inject CorrelationId correlationId;

  @Override
  public void filter(ContainerRequestContext request) {
    String header = request.getHeaderString(CorrelationId.HEADER);
    String value =
        header != null && SAFE.matcher(header).matches() ? header : CorrelationId.newValue();
    correlationId.set(value);
    MDC.put(CorrelationId.MDC_KEY, value);
  }

  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    response.getHeaders().putSingle(CorrelationId.HEADER, correlationId.get());
    MDC.remove(CorrelationId.MDC_KEY);
    MDC.remove("tenant_id");
  }
}
