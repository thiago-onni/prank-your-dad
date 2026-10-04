package br.gov.sus.nexus.fhir.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Executa trabalho JDBC em uma transação com {@code app.tenant_id} definido via {@code set_config}
 * (escopo da transação), ativando as políticas RLS do schema {@code fhir}.
 *
 * <p>Chamadas aninhadas na mesma thread e no mesmo tenant <b>participam</b> da transação externa
 * (sem commit intermediário): é o que permite processar um {@code Bundle} do tipo {@code
 * transaction} atomicamente reutilizando as interações individuais. {@link #executeIsolated} abre
 * sempre uma transação própria (auditoria), que sobrevive ao rollback da externa.
 */
@ApplicationScoped
public class TenantTransaction {

  @Inject DataSource dataSource;

  private static final ThreadLocal<Active> CURRENT = new ThreadLocal<>();

  private record Active(String tenantId, Connection connection) {}

  /** Unidade de trabalho JDBC. */
  @FunctionalInterface
  public interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  public <T> T execute(String tenantId, Work<T> work) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenantId obrigatório");
    }
    Active active = CURRENT.get();
    if (active != null) {
      if (!active.tenantId().equals(tenantId)) {
        throw new IllegalStateException("Transação aninhada com tenant diferente");
      }
      try {
        return work.run(active.connection());
      } catch (SQLException e) {
        throw new PersistenceException("Falha de acesso ao banco FHIR", e);
      }
    }
    return executeIsolated(tenantId, work);
  }

  /** Sempre em transação própria, mesmo quando há uma transação ativa na thread. */
  public <T> T executeIsolated(String tenantId, Work<T> work) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("tenantId obrigatório");
    }
    try (Connection c = dataSource.getConnection()) {
      boolean previousAutoCommit = c.getAutoCommit();
      c.setAutoCommit(false);
      try {
        try (PreparedStatement ps =
            c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
          ps.setString(1, tenantId);
          ps.execute();
        }
        T result = work.run(c);
        c.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(previousAutoCommit);
      }
    } catch (SQLException e) {
      throw new PersistenceException("Falha de acesso ao banco FHIR", e);
    }
  }

  /**
   * Abre uma transação que engloba todas as chamadas a {@link #execute} feitas pela mesma thread
   * durante {@code work} (unidade de trabalho atômica, ex.: Bundle transaction). Qualquer exceção
   * reverte tudo.
   */
  public <T> T atomic(String tenantId, Work<T> work) {
    if (CURRENT.get() != null) {
      return execute(tenantId, work);
    }
    return executeIsolated(
        tenantId,
        c -> {
          CURRENT.set(new Active(tenantId, c));
          try {
            return work.run(c);
          } finally {
            CURRENT.remove();
          }
        });
  }

  /** Erro de infraestrutura de persistência. */
  public static class PersistenceException extends RuntimeException {
    public PersistenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
