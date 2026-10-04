package br.gov.sus.nexus.core.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Ledger espelho de mensagens dos conectores — SEM payload (apenas referência à raw zone). */
@Entity
@Table(schema = "integration", name = "message")
public class IntegrationMessage {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "connector_id", nullable = false)
  public String connectorId;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "source_record_id")
  public String sourceRecordId;

  @Column(name = "source_record_version")
  public String sourceRecordVersion;

  @Column(name = "entity_type")
  public String entityType;

  @Column(nullable = false)
  public String status;

  @Column(name = "raw_ref")
  public String rawRef;

  @Column(name = "raw_sha256")
  public String rawSha256;

  @Column(name = "correlation_id")
  public String correlationId;

  @Column(name = "received_at", nullable = false)
  public Instant receivedAt;

  @Column(name = "processed_at")
  public Instant processedAt;

  @Column(nullable = false)
  public int attempts;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "last_error")
  public Map<String, Object> lastError;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Version public long version;
}
