package br.gov.sus.nexus.connectors.sdk.ledger;

import br.gov.sus.nexus.connectors.sdk.api.ErrorDetails;
import br.gov.sus.nexus.connectors.sdk.api.Period;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Ledger JDBC (tabela {@code integration_message}; DDL em {@code db/integration_message.sql},
 * compatível com PostgreSQL e H2). Opcional: requer um {@link DataSource} (ex.: Agroal).
 */
public class JdbcIntegrationMessageLedger implements IntegrationMessageLedger {

  private static final String UPSERT =
      """
      MERGE INTO integration_message (id, connector_id, source_system, source_record_id,
        source_record_version, entity_type, status, raw_ref, raw_sha256, correlation_id,
        received_at, processed_at, attempts, error_code, error_message, error_stage, error_at)
      KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String UPSERT_PG =
      """
      INSERT INTO integration_message (id, connector_id, source_system, source_record_id,
        source_record_version, entity_type, status, raw_ref, raw_sha256, correlation_id,
        received_at, processed_at, attempts, error_code, error_message, error_stage, error_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, raw_ref = EXCLUDED.raw_ref,
        raw_sha256 = EXCLUDED.raw_sha256, processed_at = EXCLUDED.processed_at,
        attempts = EXCLUDED.attempts, error_code = EXCLUDED.error_code,
        error_message = EXCLUDED.error_message, error_stage = EXCLUDED.error_stage,
        error_at = EXCLUDED.error_at
      """;

  private static final String SELECT =
      "SELECT id, connector_id, source_system, source_record_id, source_record_version, entity_type,"
          + " status, raw_ref, raw_sha256, correlation_id, received_at, processed_at, attempts,"
          + " error_code, error_message, error_stage, error_at FROM integration_message";

  private final DataSource dataSource;
  private final boolean postgres;

  public JdbcIntegrationMessageLedger(DataSource dataSource) {
    this.dataSource = dataSource;
    this.postgres = detectPostgres(dataSource);
  }

  private static boolean detectPostgres(DataSource dataSource) {
    try (Connection c = dataSource.getConnection()) {
      return c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
    } catch (SQLException e) {
      throw new IllegalStateException("não foi possível conectar ao ledger JDBC", e);
    }
  }

  /** Cria a tabela se não existir (útil em dev; em produção use Flyway). */
  public void createSchemaIfMissing() {
    try (InputStream in =
            JdbcIntegrationMessageLedger.class
                .getClassLoader()
                .getResourceAsStream("db/integration_message.sql");
        Connection c = dataSource.getConnection();
        Statement st = c.createStatement()) {
      if (in == null) throw new IllegalStateException("db/integration_message.sql não encontrado");
      for (String ddl : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!ddl.isBlank()) st.execute(ddl);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("falha ao criar schema do ledger", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public IntegrationMessage save(IntegrationMessage m) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(postgres ? UPSERT_PG : UPSERT)) {
      ErrorDetails err = m.lastError();
      ps.setString(1, m.id());
      ps.setString(2, m.connectorId());
      ps.setString(3, m.sourceSystem());
      ps.setString(4, m.sourceRecordId());
      ps.setString(5, m.sourceRecordVersion());
      ps.setString(6, m.entityType());
      ps.setString(7, m.status().name());
      ps.setString(8, m.rawRef());
      ps.setString(9, m.rawSha256());
      ps.setString(10, m.correlationId());
      ps.setTimestamp(11, Timestamp.from(m.receivedAt()));
      ps.setTimestamp(12, m.processedAt() == null ? null : Timestamp.from(m.processedAt()));
      ps.setInt(13, m.attempts());
      ps.setString(14, err == null ? null : err.code());
      ps.setString(15, err == null ? null : err.message());
      ps.setString(16, err == null ? null : err.stage());
      ps.setTimestamp(
          17, err == null || err.occurredAt() == null ? null : Timestamp.from(err.occurredAt()));
      ps.executeUpdate();
      return m;
    } catch (SQLException e) {
      throw new IllegalStateException("falha ao gravar integration_message " + m.id(), e);
    }
  }

  @Override
  public Optional<IntegrationMessage> findById(String id) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("falha ao ler integration_message " + id, e);
    }
  }

  @Override
  public List<IntegrationMessage> findByStatus(IntegrationMessageStatus status, int limit) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(SELECT + " WHERE status = ? ORDER BY received_at LIMIT ?")) {
      ps.setString(1, status.name());
      ps.setInt(2, limit);
      try (ResultSet rs = ps.executeQuery()) {
        List<IntegrationMessage> out = new ArrayList<>();
        while (rs.next()) out.add(map(rs));
        return out;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("falha ao listar integration_message", e);
    }
  }

  @Override
  public long count(String entityType, IntegrationMessageStatus status, Period period) {
    StringBuilder sql =
        new StringBuilder("SELECT COUNT(*) FROM integration_message WHERE status = ?");
    if (entityType != null) sql.append(" AND entity_type = ?");
    if (period != null) sql.append(" AND received_at >= ? AND received_at < ?");
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql.toString())) {
      int i = 1;
      ps.setString(i++, status.name());
      if (entityType != null) ps.setString(i++, entityType);
      if (period != null) {
        ps.setTimestamp(i++, Timestamp.from(period.start()));
        ps.setTimestamp(i, Timestamp.from(period.end()));
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("falha ao contar integration_message", e);
    }
  }

  private static IntegrationMessage map(ResultSet rs) throws SQLException {
    String errCode = rs.getString("error_code");
    Timestamp errAt = rs.getTimestamp("error_at");
    int attempts = rs.getInt("attempts");
    ErrorDetails err =
        errCode == null
            ? null
            : new ErrorDetails(
                rs.getString("id"),
                errCode,
                rs.getString("error_message"),
                rs.getString("error_stage"),
                errAt == null ? null : errAt.toInstant(),
                attempts);
    Timestamp processed = rs.getTimestamp("processed_at");
    return new IntegrationMessage(
        rs.getString("id"),
        rs.getString("connector_id"),
        rs.getString("source_system"),
        rs.getString("source_record_id"),
        rs.getString("source_record_version"),
        rs.getString("entity_type"),
        IntegrationMessageStatus.valueOf(rs.getString("status")),
        rs.getString("raw_ref"),
        rs.getString("raw_sha256"),
        rs.getString("correlation_id"),
        toInstant(rs.getTimestamp("received_at")),
        processed == null ? null : processed.toInstant(),
        attempts,
        err);
  }

  private static Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
