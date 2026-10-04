package br.gov.sus.nexus.core.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Resultado de reconciliação fonte × barramento por conector/entidade/período. */
@Entity
@Table(schema = "integration", name = "reconciliation")
public class Reconciliation {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "connector_id", nullable = false)
  public String connectorId;

  @Column(name = "entity_type", nullable = false)
  public String entityType;

  @Column(name = "period_start", nullable = false)
  public Instant periodStart;

  @Column(name = "period_end", nullable = false)
  public Instant periodEnd;

  @Column(name = "source_count", nullable = false)
  public int sourceCount;

  @Column(name = "bus_count", nullable = false)
  public int busCount;

  @Column(nullable = false)
  public int gap;

  @Column(name = "checked_at", nullable = false)
  public Instant checkedAt = Instant.now();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  public Map<String, Object> details = Map.of();
}
