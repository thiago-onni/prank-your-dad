package br.gov.sus.nexus.core.production.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** {@code production.production_outcome}: retorno do processamento oficial. */
@Entity
@Table(schema = "production", name = "production_outcome")
public class ProductionOutcome {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "record_id")
  public String recordId;

  @Column(name = "batch_id")
  public String batchId;

  @Column(nullable = false)
  public String outcome;

  @Column(name = "reason_code")
  public String reasonCode;

  public String reason;

  @Column(name = "paid_amount")
  public BigDecimal paidAmount;

  @Column(name = "approved_quantity")
  public Integer approvedQuantity;

  @Column(name = "protocol_number")
  public String protocolNumber;

  @Column(name = "processed_at", nullable = false)
  public Instant processedAt;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "source_record_id", nullable = false)
  public String sourceRecordId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
