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
 */
@ApplicationScoped
public class TenantTransaction {

  @Inject DataSource dataSource;

  /** Unidade de trabalho JDBC. */
  @FunctionalInterface
  public interface Work<T> {
    T run(Connection connection) throws SQLException;
  }

  public <T> T execute(String tenantId, Work<T> work) {
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

  /** Erro de infraestrutura de persistência. */
  public static class PersistenceException extends RuntimeException {
    public PersistenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
