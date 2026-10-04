package br.gov.sus.nexus.fhir.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
    return searchHits(c, query, tenantId).stream().map(SearchHit::resource).toList();
  }

  /** Resultado de busca com a chave de ordenação (para o cursor com {@code _sort}). */
  public record SearchHit(StoredResource resource, java.time.Instant sortKey) {}

  public List<SearchHit> searchHits(Connection c, SearchQuery query, String tenantId)
      throws SQLException {
    SearchSqlBuilder.Sql sql = SearchSqlBuilder.build(query, tenantId);
    try (PreparedStatement ps = c.prepareStatement(sql.text())) {
      int i = 1;
      for (Object p : sql.params()) {
        ps.setObject(i++, p);
      }
      try (ResultSet rs = ps.executeQuery()) {
        List<SearchHit> list = new ArrayList<>();
        while (rs.next()) {
          Timestamp key = rs.getTimestamp(9);
          list.add(new SearchHit(map(rs), key == null ? null : key.toInstant()));
        }
        return list;
      }
    }
  }

  public long count(Connection c, SearchQuery query, String tenantId) throws SQLException {
    SearchSqlBuilder.Sql sql = SearchSqlBuilder.buildCount(query, tenantId);
    try (PreparedStatement ps = c.prepareStatement(sql.text())) {
      int i = 1;
      for (Object p : sql.params()) {
        ps.setObject(i++, p);
      }
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    }
  }

  /**
   * Alvos de referência indexados de um conjunto de recursos para um parâmetro ({@code _include}).
   */
  public List<IndexEntry.Ref> referenceTargets(Connection c, List<String> resourceIds, String param)
      throws SQLException {
    List<IndexEntry.Ref> out = new ArrayList<>();
    if (resourceIds.isEmpty()) {
      return out;
    }
    String sql =
        "SELECT DISTINCT target_type, target_id FROM fhir.fhir_idx_reference"
            + " WHERE param = ? AND resource_id = ANY(?)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, param);
      ps.setArray(2, textArray(c, resourceIds));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(new IndexEntry.Ref(param, rs.getString(1), rs.getString(2)));
        }
      }
    }
    return out;
  }

  /**
   * Exclusão lógica: nova versão com {@code deleted = true} (conteúdo preservado para o histórico),
   * índices removidos. Não há exclusão física.
   */
  public void markDeleted(Connection c, StoredResource deletedVersion) throws SQLException {
    update(c, deletedVersion, List.of());
  }

  /** Página de histórico de tipo ({@code type != null}) ou de sistema ({@code type == null}). */
  public List<StoredResource> historyPage(
      Connection c, String tenantId, String type, HistoryCursor after, Instant since, int limit)
      throws SQLException {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLUMNS)
            .append(" FROM fhir.fhir_resource_history WHERE tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenantId);
    if (type != null) {
      sql.append(" AND resource_type = ?");
      params.add(type);
    }
    if (since != null) {
      sql.append(" AND last_updated >= ?");
      params.add(Timestamp.from(since));
    }
    if (after != null) {
      sql.append(
          " AND (last_updated < ? OR (last_updated = ? AND (id > ? OR (id = ? AND version_id"
              + " < ?))))");
      Timestamp ts = Timestamp.from(after.lastUpdated());
      params.add(ts);
      params.add(ts);
      params.add(after.id());
      params.add(after.id());
      params.add(after.versionId());
    }
    sql.append(" ORDER BY last_updated DESC, id ASC, version_id DESC LIMIT ?");
    params.add(limit);
    try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
      int i = 1;
      for (Object p : params) {
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

  /** Posição (keyset) na listagem de histórico de tipo/sistema. */
  public record HistoryCursor(Instant lastUpdated, String id, int versionId) {}

  /**
   * Página do compartimento de um paciente ({@code $everything}): o próprio Patient e todo recurso
   * dos tipos informados cujo parâmetro {@code patient} aponta para ele, ordenados por (tipo, id).
   */
  public List<StoredResource> compartmentPage(
      Connection c,
      String tenantId,
      String patientId,
      List<String> types,
      Instant since,
      String afterType,
      String afterId,
      int limit)
      throws SQLException {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLUMNS)
            .append(
                " FROM fhir.fhir_resource r WHERE r.tenant_id = ? AND r.deleted = false AND"
                    + " r.resource_type = ANY(?) AND ((r.resource_type = 'Patient' AND r.id = ?)"
                    + " OR EXISTS (SELECT 1 FROM fhir.fhir_idx_reference t WHERE t.resource_id ="
                    + " r.id AND t.param = 'patient' AND t.target_type = 'Patient' AND"
                    + " t.target_id = ?))");
    List<Object> params = new ArrayList<>();
    params.add(tenantId);
    params.add(textArray(c, types));
    params.add(patientId);
    params.add(patientId);
    if (since != null) {
      sql.append(" AND r.last_updated >= ?");
      params.add(Timestamp.from(since));
    }
    if (afterType != null && afterId != null) {
      sql.append(" AND (r.resource_type > ? OR (r.resource_type = ? AND r.id > ?))");
      params.add(afterType);
      params.add(afterType);
      params.add(afterId);
    }
    sql.append(" ORDER BY r.resource_type ASC, r.id ASC LIMIT ?");
    params.add(limit);
    try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
      int i = 1;
      for (Object p : params) {
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

  /** Metadados de um Binary (o conteúdo fica no object storage). */
  public record BinaryMeta(
      String id, String tenantId, String contentType, long sizeBytes, String sha256, String key) {}

  public void insertBinary(Connection c, BinaryMeta meta) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO fhir.fhir_binary (id, tenant_id, content_type, size_bytes, sha256,"
                + " storage_key) VALUES (?, ?, ?, ?, ?, ?)")) {
      ps.setString(1, meta.id());
      ps.setString(2, meta.tenantId());
      ps.setString(3, meta.contentType());
      ps.setLong(4, meta.sizeBytes());
      ps.setString(5, meta.sha256());
      ps.setString(6, meta.key());
      ps.executeUpdate();
    }
  }

  public Optional<BinaryMeta> findBinary(Connection c, String id) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, content_type, size_bytes, sha256, storage_key FROM"
                + " fhir.fhir_binary WHERE id = ?")) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new BinaryMeta(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getLong(4),
                rs.getString(5),
                rs.getString(6)));
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
        List.of(
            "fhir_idx_token",
            "fhir_idx_string",
            "fhir_idx_date",
            "fhir_idx_reference",
            "fhir_idx_quantity")) {
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
                    + " target_id) VALUES (?, ?, ?, ?, ?)");
        PreparedStatement qty =
            c.prepareStatement(
                "INSERT INTO fhir.fhir_idx_quantity (resource_id, tenant_id, param, system, code,"
                    + " value) VALUES (?, ?, ?, ?, ?, ?)")) {
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
          case IndexEntry.Quantity q -> {
            qty.setString(1, r.id());
            qty.setString(2, r.tenantId());
            qty.setString(3, q.param());
            qty.setString(4, q.system());
            qty.setString(5, q.code());
            qty.setBigDecimal(6, q.value());
            qty.addBatch();
          }
        }
      }
      token.executeBatch();
      str.executeBatch();
      date.executeBatch();
      ref.executeBatch();
      qty.executeBatch();
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
