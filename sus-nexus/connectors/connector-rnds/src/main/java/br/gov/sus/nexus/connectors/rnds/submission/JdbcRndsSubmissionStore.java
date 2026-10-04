package br.gov.sus.nexus.connectors.rnds.submission;

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
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** Store JDBC (tabela {@code rnds_submission}; DDL em {@code db/rnds_submission.sql}). */
public class JdbcRndsSubmissionStore implements RndsSubmissionStore {

  private static final String COLUMNS =
      "event_id, model, source_id, integration_message_id, bundle_sha256, status, http_status,"
          + " protocol, outcome_summary, operation_outcome, attempts, created_at, updated_at,"
          + " submitted_at";

  private final DataSource dataSource;

  public JdbcRndsSubmissionStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void createSchemaIfMissing() {
    try (InputStream in =
        JdbcRndsSubmissionStore.class
            .getClassLoader()
            .getResourceAsStream("db/rnds_submission.sql")) {
      if (in == null) throw new IllegalStateException("db/rnds_submission.sql não encontrado");
      String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      try (Connection c = dataSource.getConnection();
          Statement st = c.createStatement()) {
        for (String sql : ddl.split(";")) {
          String stmt =
              sql.lines()
                  .filter(l -> !l.trim().startsWith("--"))
                  .reduce("", (a, b) -> a + "\n" + b);
          if (!stmt.isBlank()) st.execute(stmt);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (SQLException e) {
      throw new IllegalStateException("falha ao criar rnds_submission", e);
    }
  }

  @Override
  public boolean claim(RndsSubmission s) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO rnds_submission ("
                    + COLUMNS
                    + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      bind(ps, s);
      ps.executeUpdate();
      return true;
    } catch (SQLException e) {
      if (e.getSQLState() != null && e.getSQLState().startsWith("23")) return false;
      throw new IllegalStateException("falha ao registrar rnds_submission", e);
    }
  }

  @Override
  public Optional<RndsSubmission> findByEventId(String eventId) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement("SELECT " + COLUMNS + " FROM rnds_submission WHERE event_id = ?")) {
      ps.setString(1, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(read(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("falha ao ler rnds_submission", e);
    }
  }

  @Override
  public Optional<RndsSubmission> findLatestAccepted(
      String model, String sourceId, String exceptEventId) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT "
                    + COLUMNS
                    + " FROM rnds_submission WHERE model = ? AND source_id = ? AND status = ?"
                    + " AND event_id <> ? ORDER BY updated_at DESC")) {
      ps.setString(1, model);
      ps.setString(2, sourceId);
      ps.setString(3, RndsSubmissionStatus.ACCEPTED.name());
      ps.setString(4, exceptEventId == null ? "" : exceptEventId);
      ps.setMaxRows(1);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(read(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("falha ao ler rnds_submission", e);
    }
  }

  @Override
  public RndsSubmission save(RndsSubmission s) {
    try (Connection c = dataSource.getConnection()) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE rnds_submission SET model=?, source_id=?, integration_message_id=?,"
                  + " bundle_sha256=?, status=?, http_status=?, protocol=?, outcome_summary=?,"
                  + " operation_outcome=?, attempts=?, created_at=?, updated_at=?, submitted_at=?"
                  + " WHERE event_id=?")) {
        ps.setString(1, s.model());
        ps.setString(2, s.sourceId());
        ps.setString(3, s.integrationMessageId());
        ps.setString(4, s.bundleSha256());
        ps.setString(5, s.status().name());
        setInt(ps, 6, s.httpStatus());
        ps.setString(7, s.protocol());
        ps.setString(8, s.outcomeSummary());
        ps.setString(9, s.operationOutcome());
        ps.setInt(10, s.attempts());
        ps.setTimestamp(11, ts(s.createdAt()));
        ps.setTimestamp(12, ts(s.updatedAt()));
        ps.setTimestamp(13, ts(s.submittedAt()));
        ps.setString(14, s.eventId());
        if (ps.executeUpdate() > 0) return s;
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO rnds_submission ("
                  + COLUMNS
                  + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
        bind(ps, s);
        ps.executeUpdate();
      }
      return s;
    } catch (SQLException e) {
      throw new IllegalStateException("falha ao gravar rnds_submission", e);
    }
  }

  @Override
  public List<RndsSubmission> list(String model, Period period) {
    StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM rnds_submission WHERE 1=1");
    if (model != null) sql.append(" AND model = ?");
    if (period != null) sql.append(" AND created_at >= ? AND created_at < ?");
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql.toString())) {
      int i = 1;
      if (model != null) ps.setString(i++, model);
      if (period != null) {
        ps.setTimestamp(i++, ts(period.start()));
        ps.setTimestamp(i, ts(period.end()));
      }
      List<RndsSubmission> out = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.add(read(rs));
      }
      return out;
    } catch (SQLException e) {
      throw new IllegalStateException("falha ao listar rnds_submission", e);
    }
  }

  @Override
  public void clear() {
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement()) {
      st.executeUpdate("DELETE FROM rnds_submission");
    } catch (SQLException e) {
      throw new IllegalStateException("falha ao limpar rnds_submission", e);
    }
  }

  private static void bind(PreparedStatement ps, RndsSubmission s) throws SQLException {
    ps.setString(1, s.eventId());
    ps.setString(2, s.model());
    ps.setString(3, s.sourceId());
    ps.setString(4, s.integrationMessageId());
    ps.setString(5, s.bundleSha256());
    ps.setString(6, s.status().name());
    setInt(ps, 7, s.httpStatus());
    ps.setString(8, s.protocol());
    ps.setString(9, s.outcomeSummary());
    ps.setString(10, s.operationOutcome());
    ps.setInt(11, s.attempts());
    ps.setTimestamp(12, ts(s.createdAt()));
    ps.setTimestamp(13, ts(s.updatedAt()));
    ps.setTimestamp(14, ts(s.submittedAt()));
  }

  private static RndsSubmission read(ResultSet rs) throws SQLException {
    int http = rs.getInt("http_status");
    Integer httpStatus = rs.wasNull() ? null : http;
    return new RndsSubmission(
        rs.getString("event_id"),
        rs.getString("model"),
        rs.getString("source_id"),
        rs.getString("integration_message_id"),
        rs.getString("bundle_sha256"),
        RndsSubmissionStatus.valueOf(rs.getString("status")),
        httpStatus,
        rs.getString("protocol"),
        rs.getString("outcome_summary"),
        rs.getString("operation_outcome"),
        rs.getInt("attempts"),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")),
        instant(rs.getTimestamp("submitted_at")));
  }

  private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
    if (value == null) ps.setNull(index, Types.INTEGER);
    else ps.setInt(index, value);
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
