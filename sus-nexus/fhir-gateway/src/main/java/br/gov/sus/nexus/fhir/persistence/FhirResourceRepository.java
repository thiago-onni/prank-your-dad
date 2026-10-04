package br.gov.sus.nexus.fhir.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Repositório JDBC do schema {@code fhir}. Todos os métodos recebem a {@link Connection} de uma
 * {@link TenantTransaction} (RLS ativo). O conteúdo é armazenado como JSONB; os índices de busca
 * são reconstruídos a cada versão.
 */
@ApplicationScoped
public class FhirResourceRepository {

  private static final String COLUMNS =
      "id, tenant_id, resource_type, version_id, last_updated, profile, content::text, deleted";

  public Optional<StoredResource> findCurrent(Connection c, String type, String id)
      throws SQLException {
    return findCurrent(c, type, id, false);
  }

  public Optional<StoredResource> findCurrentForUpdate(Connection c, String type, String id)
      throws SQLException {
    return findCurrent(c, type, id, true);
  }

  private Optional<StoredResource> findCurrent(
      Connection c, String type, String id, boolean forUpdate) throws SQLException {
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM fhir.fhir_resource WHERE resource_type = ? AND id = ?"
            + (forUpdate ? " FOR UPDATE" : "");
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, type);
      ps.setString(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  public Optional<StoredResource> findVersion(Connection c, String type, String id, int versionId)
      throws SQLException {
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM fhir.fhir_resource_history WHERE resource_type = ? AND id = ? AND version_id = ?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, type);
      ps.setString(2, id);
      ps.setInt(3, versionId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  public List<StoredResource> history(Connection c, String type, String id) throws SQLException {
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM fhir.fhir_resource_history WHERE resource_type = ? AND id = ?"
            + " ORDER BY version_id DESC";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, type);
      ps.setString(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        List<StoredResource> list = new ArrayList<>();
        while (rs.next()) {
          list.add(map(rs));
        }
        return list;
      }
    }
  }

  /** Insere a primeira versão de um recurso (e seu histórico e índices). */
  public void insert(Connection c, StoredResource r, List<IndexEntry> index) throws SQLException {
    String sql =
        "INSERT INTO fhir.fhir_resource (id, tenant_id, resource_type, version_id, last_updated,"
            + " profile, content, deleted) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, c, r);
      ps.executeUpdate();
    }
    appendHistory(c, r);
    replaceIndex(c, r, index);
  }

  /** Grava uma nova versão de um recurso existente. */
  public void update(Connection c, StoredResource r, List<IndexEntry> index) throws SQLException {
    String sql =
        "UPDATE fhir.fhir_resource SET version_id = ?, last_updated = ?, profile = ?,"
            + " content = ?::jsonb, deleted = ? WHERE resource_type = ? AND id = ?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setInt(1, r.versionId());
      ps.setTimestamp(2, Timestamp.from(r.lastUpdated()));
      ps.setArray(3, textArray(c, r.profiles()));
      ps.setString(4, r.content());
      ps.setBoolean(5, r.deleted());
      ps.setString(6, r.resourceType());
      ps.setString(7, r.id());
      if (ps.executeUpdate() != 1) {
        throw new SQLException("Recurso não encontrado para atualização");
      }
    }
    appendHistory(c, r);
    replaceIndex(c, r, index);
  }

  public List<StoredResource> search(Connection c, SearchQuery query, String tenantId)
      throws SQLException {
    SearchSqlBuilder.Sql sql = SearchSqlBuilder.build(query, tenantId);
    try (PreparedStatement ps = c.prepareStatement(sql.text())) {
      int i = 1;
      for (Object p : sql.params()) {
        ps.setObject(i++, p);
      }
      try (ResultSet rs = ps.executeQuery()) {
        List<StoredResource> list = new ArrayList<>();
        while (rs.next()) {
          list.add(map(rs));
        }
        return list;
      }
    }
  }

  private void appendHistory(Connection c, StoredResource r) throws SQLException {
    String sql =
        "INSERT INTO fhir.fhir_resource_history (id, tenant_id, resource_type, version_id,"
            + " last_updated, profile, content, deleted) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, c, r);
      ps.executeUpdate();
    }
  }

  private void replaceIndex(Connection c, StoredResource r, List<IndexEntry> index)
      throws SQLException {
    for (String table :
        List.of("fhir_idx_token", "fhir_idx_string", "fhir_idx_date", "fhir_idx_reference")) {
      try (PreparedStatement ps =
          c.prepareStatement("DELETE FROM fhir." + table + " WHERE resource_id = ?")) {
        ps.setString(1, r.id());
        ps.executeUpdate();
      }
    }
    try (PreparedStatement token =
            c.prepareStatement(
                "INSERT INTO fhir.fhir_idx_token (resource_id, tenant_id, param, system, code)"
                    + " VALUES (?, ?, ?, ?, ?)");
        PreparedStatement str =
            c.prepareStatement(
                "INSERT INTO fhir.fhir_idx_string (resource_id, tenant_id, param, value_norm)"
                    + " VALUES (?, ?, ?, ?)");
        PreparedStatement date =
            c.prepareStatement(
                "INSERT INTO fhir.fhir_idx_date (resource_id, tenant_id, param, low, high)"
                    + " VALUES (?, ?, ?, ?, ?)");
        PreparedStatement ref =
            c.prepareStatement(
                "INSERT INTO fhir.fhir_idx_reference (resource_id, tenant_id, param, target_type,"
                    + " target_id) VALUES (?, ?, ?, ?, ?)")) {
      for (IndexEntry e : index) {
        switch (e) {
          case IndexEntry.Token t -> {
            token.setString(1, r.id());
            token.setString(2, r.tenantId());
            token.setString(3, t.param());
            token.setString(4, t.system());
            token.setString(5, t.code());
            token.addBatch();
          }
          case IndexEntry.Str s -> {
            str.setString(1, r.id());
            str.setString(2, r.tenantId());
            str.setString(3, s.param());
            str.setString(4, s.valueNorm());
            str.addBatch();
          }
          case IndexEntry.Date d -> {
            date.setString(1, r.id());
            date.setString(2, r.tenantId());
            date.setString(3, d.param());
            date.setTimestamp(4, Timestamp.from(d.low()));
            date.setTimestamp(5, Timestamp.from(d.high()));
            date.addBatch();
          }
          case IndexEntry.Ref f -> {
            ref.setString(1, r.id());
            ref.setString(2, r.tenantId());
            ref.setString(3, f.param());
            ref.setString(4, f.targetType());
            ref.setString(5, f.targetId());
            ref.addBatch();
          }
        }
      }
      token.executeBatch();
      str.executeBatch();
      date.executeBatch();
      ref.executeBatch();
    }
  }

  private static void bind(PreparedStatement ps, Connection c, StoredResource r)
      throws SQLException {
    ps.setString(1, r.id());
    ps.setString(2, r.tenantId());
    ps.setString(3, r.resourceType());
    ps.setInt(4, r.versionId());
    ps.setTimestamp(5, Timestamp.from(r.lastUpdated()));
    ps.setArray(6, textArray(c, r.profiles()));
    ps.setString(7, r.content());
    ps.setBoolean(8, r.deleted());
  }

  private static Array textArray(Connection c, List<String> values) throws SQLException {
    return c.createArrayOf("text", values.toArray(new String[0]));
  }

  private static StoredResource map(ResultSet rs) throws SQLException {
    Array profile = rs.getArray(6);
    List<String> profiles =
        profile == null ? List.of() : Arrays.asList((String[]) profile.getArray());
    return new StoredResource(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getInt(4),
        rs.getTimestamp(5).toInstant(),
        profiles,
        rs.getString(7),
        rs.getBoolean(8));
  }
}
