package br.gov.sus.nexus.core.platform.tenant;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.function.Supplier;

/**
 * Helpers programáticos de transação com tenant aplicado ({@code set_config('app.tenant_id', ?,
 * true)}). Usados pelo interceptor de {@link TenantTransactional}, por escritas em transação
 * própria (ex.: access_log) e por processamento fora de requisição HTTP (consumidores, jobs).
 */
@ApplicationScoped
public class TenantTransactions {

  static final String SET_TENANT_SQL = "select set_config('app.tenant_id', ?1, true)";

  @Inject EntityManager entityManager;
  @Inject TenantContext tenantContext;

  /** Aplica o tenant corrente na transação ativa. */
  public void applyCurrentTenant() {
    apply(tenantContext.require());
  }

  /** Aplica o tenant informado na transação ativa (escopo da transação apenas). */
  public void apply(String tenantId) {
    entityManager.createNativeQuery(SET_TENANT_SQL).setParameter(1, tenantId).getSingleResult();
  }

  /** Executa em transação existente (ou nova) com o tenant corrente aplicado. */
  public <T> T joining(Supplier<T> work) {
    return QuarkusTransaction.joiningExisting()
        .call(
            () -> {
              applyCurrentTenant();
              return work.get();
            });
  }

  /** Executa em transação NOVA (REQUIRES_NEW) com o tenant corrente aplicado. */
  public <T> T requiringNew(Supplier<T> work) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              applyCurrentTenant();
              return work.get();
            });
  }

  /**
   * Executa {@code work} como o tenant informado, ativando o contexto de requisição se necessário
   * (uso em consumidores de eventos, jobs e testes).
   */
  public <T> T runAs(String tenantId, Supplier<T> work) {
    ManagedContext requestContext = Arc.container().requestContext();
    boolean activated = false;
    if (!requestContext.isActive()) {
      requestContext.activate();
      activated = true;
    }
    try {
      TenantContext ctx = Arc.container().instance(TenantContext.class).get();
      ctx.set(tenantId);
      return work.get();
    } finally {
      if (activated) {
        requestContext.terminate();
      }
    }
  }
}
