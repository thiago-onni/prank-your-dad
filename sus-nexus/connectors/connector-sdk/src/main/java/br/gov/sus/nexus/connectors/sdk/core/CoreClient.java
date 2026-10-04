package br.gov.sus.nexus.connectors.sdk.core;

import br.gov.sus.nexus.connectors.sdk.core.dto.AppointmentRegistration;
import br.gov.sus.nexus.connectors.sdk.core.dto.AppointmentResponse;
import br.gov.sus.nexus.connectors.sdk.core.dto.CitizenRegistration;
import br.gov.sus.nexus.connectors.sdk.core.dto.CodeUpsertBatch;
import br.gov.sus.nexus.connectors.sdk.core.dto.HealthUnitUpsertBatch;
import br.gov.sus.nexus.connectors.sdk.core.dto.IdentityResolution;
import br.gov.sus.nexus.connectors.sdk.core.dto.UpsertResult;
import br.gov.sus.nexus.connectors.sdk.util.Pii;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Fachada do core com retry exponencial + circuit breaker (SmallRye Fault Tolerance). Só erros
 * transitórios ({@link CoreClientException#isTransient()}) disparam retry; 4xx vão para DLQ.
 *
 * <p>Os parâmetros das anotações podem ser sobrescritos por configuração MicroProfile, ex.: {@code
 * br.gov.sus.nexus.connectors.sdk.core.CoreClient/Retry/maxRetries=3}.
 */
@ApplicationScoped
public class CoreClient {

  private static final Logger LOG = Logger.getLogger(CoreClient.class);

  private final CoreApi api;

  @Inject
  public CoreClient(@RestClient CoreApi api) {
    this.api = api;
  }

  @Retry(
      maxRetries = 4,
      delay = 500,
      delayUnit = ChronoUnit.MILLIS,
      jitter = 200,
      jitterDelayUnit = ChronoUnit.MILLIS,
      retryOn = CoreClientException.class,
      abortOn = CorePermanentException.class)
  @CircuitBreaker(
      requestVolumeThreshold = 8,
      failureRatio = 0.5,
      delay = 10_000,
      failOn = CoreClientException.class)
  public IdentityResolution registerCitizen(
      String idempotencyKey, String correlationId, CitizenRegistration body) {
    return call(
        () -> api.registerCitizen(idempotencyKey, correlationId, body), IdentityResolution.class);
  }

  @Retry(
      maxRetries = 4,
      delay = 500,
      delayUnit = ChronoUnit.MILLIS,
      jitter = 200,
      jitterDelayUnit = ChronoUnit.MILLIS,
      retryOn = CoreClientException.class,
      abortOn = CorePermanentException.class)
  @CircuitBreaker(
      requestVolumeThreshold = 8,
      failureRatio = 0.5,
      delay = 10_000,
      failOn = CoreClientException.class)
  public AppointmentResponse registerAppointment(
      String idempotencyKey, String correlationId, AppointmentRegistration body) {
    return call(
        () -> api.registerAppointment(idempotencyKey, correlationId, body),
        AppointmentResponse.class);
  }

  @Retry(
      maxRetries = 4,
      delay = 1000,
      delayUnit = ChronoUnit.MILLIS,
      jitter = 300,
      jitterDelayUnit = ChronoUnit.MILLIS,
      retryOn = CoreClientException.class,
      abortOn = CorePermanentException.class)
  @CircuitBreaker(
      requestVolumeThreshold = 8,
      failureRatio = 0.5,
      delay = 10_000,
      failOn = CoreClientException.class)
  public UpsertResult upsertHealthUnits(
      String idempotencyKey, String correlationId, HealthUnitUpsertBatch body) {
    return call(
        () -> api.upsertHealthUnits(idempotencyKey, correlationId, body), UpsertResult.class);
  }

  @Retry(
      maxRetries = 4,
      delay = 1000,
      delayUnit = ChronoUnit.MILLIS,
      jitter = 300,
      jitterDelayUnit = ChronoUnit.MILLIS,
      retryOn = CoreClientException.class,
      abortOn = CorePermanentException.class)
  @CircuitBreaker(
      requestVolumeThreshold = 8,
      failureRatio = 0.5,
      delay = 10_000,
      failOn = CoreClientException.class)
  public UpsertResult upsertCodes(
      String system, String idempotencyKey, String correlationId, CodeUpsertBatch body) {
    return call(
        () -> api.upsertCodes(system, idempotencyKey, correlationId, body), UpsertResult.class);
  }

  private <T> T call(Supplier<Response> request, Class<T> type) {
    try (Response response = invoke(request)) {
      int status = response.getStatus();
      if (status >= 200 && status < 300) {
        if (type == Void.class || !response.hasEntity()) return null;
        return response.readEntity(type);
      }
      String problem =
          response.hasEntity() ? Pii.maskText(response.readEntity(String.class)) : null;
      CoreClientException ex = CoreClientException.fromStatus(status, problem);
      if (!ex.isTransient()) {
        throw new CorePermanentException(ex);
      }
      throw ex;
    }
  }

  private static Response invoke(Supplier<Response> request) {
    try {
      return request.get();
    } catch (WebApplicationException e) {
      // O mapper padrão do REST client converte 4xx/5xx em exceção; usamos a resposta original.
      Response response = e.getResponse();
      if (response == null) throw CoreClientException.network(e);
      return response;
    } catch (ProcessingException e) {
      LOG.warnf("falha de rede ao chamar o core: %s", Pii.maskText(String.valueOf(e.getMessage())));
      throw CoreClientException.network(e);
    }
  }

  /** Erro 4xx: aborta retry e vai para DLQ. */
  public static class CorePermanentException extends CoreClientException {
    public CorePermanentException(CoreClientException cause) {
      super(cause.status(), cause.problem(), false, cause);
    }
  }
}
