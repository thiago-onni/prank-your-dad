package br.gov.sus.nexus.core.audit.infrastructure;

import br.gov.sus.nexus.core.audit.api.AccessLogService;
import br.gov.sus.nexus.core.audit.api.AccessRecord;
import br.gov.sus.nexus.core.audit.api.AuditedAccess;
import br.gov.sus.nexus.core.platform.correlation.CorrelationId;
import br.gov.sus.nexus.core.platform.errors.ProblemException;
import br.gov.sus.nexus.core.platform.security.CurrentActor;
import br.gov.sus.nexus.core.platform.security.Purpose;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.PathParam;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/** Interceptor de {@link AuditedAccess}: grava access_log (allow/deny) nas leituras de cidadão. */
@AuditedAccess
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 10)
public class AuditedAccessInterceptor {

  @Inject AccessLogService accessLogService;
  @Inject CurrentActor currentActor;
  @Inject CorrelationId correlationId;

  @AroundInvoke
  public Object aroundInvoke(InvocationContext ctx) throws Exception {
    AuditedAccess cfg = resolve(ctx.getMethod());
    String citizenId = pathParam(ctx, cfg.citizenIdParam());
    Purpose purpose = currentActor.purpose().orElse(null);

    if (cfg.requirePurpose() && purpose == null) {
      record(cfg, citizenId, null, false, "finalidade ausente");
      throw new ProblemException(
          400,
          "Finalidade obrigatória",
          "header X-Purpose-Of-Use é obrigatório para acesso a dado de cidadão",
          "urn:sus-nexus:problem:purpose-required");
    }
    try {
      Object result = ctx.proceed();
      record(cfg, citizenId, purpose, true, null);
      return result;
    } catch (ForbiddenException | UnauthorizedException e) {
      record(cfg, citizenId, purpose, false, e.getClass().getSimpleName());
      throw e;
    }
  }

  private void record(
      AuditedAccess cfg, String citizenId, Purpose purpose, boolean allowed, String note) {
    accessLogService.record(
        new AccessRecord(
            currentActor.actorId(),
            currentActor.roles(),
            cfg.action(),
            cfg.resourceType(),
            citizenId,
            citizenId,
            purpose,
            allowed,
            currentActor.breakGlass(),
            note,
            correlationId.get()));
  }

  private static AuditedAccess resolve(Method method) {
    AuditedAccess a = method.getAnnotation(AuditedAccess.class);
    if (a == null) {
      a = method.getDeclaringClass().getAnnotation(AuditedAccess.class);
    }
    return a;
  }

  private static String pathParam(InvocationContext ctx, String name) {
    Parameter[] params = ctx.getMethod().getParameters();
    Object[] values = ctx.getParameters();
    for (int i = 0; i < params.length; i++) {
      PathParam pp = params[i].getAnnotation(PathParam.class);
      if (pp != null && pp.value().equals(name) && values[i] != null) {
        return values[i].toString();
      }
    }
    return null;
  }
}
