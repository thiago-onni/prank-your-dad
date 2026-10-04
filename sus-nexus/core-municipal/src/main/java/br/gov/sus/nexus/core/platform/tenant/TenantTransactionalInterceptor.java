package br.gov.sus.nexus.core.platform.tenant;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/** Interceptor de {@link TenantTransactional}: transação JTA + {@code SET LOCAL app.tenant_id}. */
@TenantTransactional
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 200)
public class TenantTransactionalInterceptor {

  @Inject TenantTransactions transactions;

  @AroundInvoke
  public Object aroundInvoke(InvocationContext ctx) throws Exception {
    try {
      return QuarkusTransaction.joiningExisting()
          .call(
              () -> {
                transactions.applyCurrentTenant();
                return ctx.proceed();
              });
    } catch (io.quarkus.narayana.jta.QuarkusTransactionException e) {
      // desembrulha exceções checadas do método interceptado
      if (e.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw e;
    }
  }
}
