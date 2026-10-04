package br.gov.sus.nexus.connectors.pec;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Expõe o datasource nomeado {@code pec} (Agroal) ao registro do Camel como {@code #pecDataSource}.
 * Só é resolvido em modo {@code jdbc}; com {@code quarkus.datasource.pec.active=false} nunca é
 * tocado.
 */
@ApplicationScoped
public class PecDataSources {

  @Produces
  @Singleton
  @Named("pecDataSource")
  public javax.sql.DataSource pecDataSource(@DataSource("pec") Instance<AgroalDataSource> pec) {
    return new LazyDataSource(pec);
  }

  /**
   * Adia a resolução do Agroal até o primeiro uso (evita falhar quando o datasource está inativo).
   */
  static final class LazyDataSource implements javax.sql.DataSource {
    private final Instance<AgroalDataSource> delegate;

    LazyDataSource(Instance<AgroalDataSource> delegate) {
      this.delegate = delegate;
    }

    private javax.sql.DataSource ds() {
      return delegate.get();
    }

    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
      java.sql.Connection c = ds().getConnection();
      c.setReadOnly(true);
      return c;
    }

    @Override
    public java.sql.Connection getConnection(String u, String p) throws java.sql.SQLException {
      java.sql.Connection c = ds().getConnection(u, p);
      c.setReadOnly(true);
      return c;
    }

    @Override
    public java.io.PrintWriter getLogWriter() throws java.sql.SQLException {
      return ds().getLogWriter();
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) throws java.sql.SQLException {
      ds().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws java.sql.SQLException {
      ds().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws java.sql.SQLException {
      return ds().getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() {
      return java.util.logging.Logger.getLogger("pec");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
      return ds().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException {
      return ds().isWrapperFor(iface);
    }
  }
}
