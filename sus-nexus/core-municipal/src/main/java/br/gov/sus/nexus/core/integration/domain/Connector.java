package br.gov.sus.nexus.core.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Registry de conectores (por tenant). */
@Entity
@Table(schema = "integration", name = "connector")
@IdClass(Connector.Key.class)
public class Connector {

  /** Chave composta (tenant, connector_id). */
  public static class Key implements Serializable {
    public String tenantId;
    public String connectorId;

    public Key() {}

    public Key(String tenantId, String connectorId) {
      this.tenantId = tenantId;
      this.connectorId = connectorId;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key k
          && Objects.equals(tenantId, k.tenantId)
          && Objects.equals(connectorId, k.connectorId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(tenantId, connectorId);
    }
  }

  @Id
  @Column(name = "tenant_id")
  public String tenantId;

  @Id
  @Column(name = "connector_id")
  public String connectorId;

  @Column(name = "connector_version", nullable = false)
  public String connectorVersion;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(nullable = false)
  public String health = "unknown";

  @Column(name = "health_detail")
  public String healthDetail;

  @Column(name = "last_message_at")
  public Instant lastMessageAt;

  @Column(name = "last_heartbeat_at")
  public Instant lastHeartbeatAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  public Map<String, Object> descriptor = Map.of();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  public Map<String, Object> metrics = Map.of();

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Version public long version;
}
