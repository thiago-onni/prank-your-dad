package br.gov.sus.nexus.core.production.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code production.production_batch_item}. */
@Entity
@Table(schema = "production", name = "production_batch_item")
public class ProductionBatchItem {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "batch_id", nullable = false)
  public String batchId;

  @Column(name = "record_id", nullable = false)
  public String recordId;

  @Column(name = "line_number", nullable = false)
  public int lineNumber;

  @Column(name = "removed_at")
  public Instant removedAt;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
