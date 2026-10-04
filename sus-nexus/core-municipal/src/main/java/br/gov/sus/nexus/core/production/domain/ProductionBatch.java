package br.gov.sus.nexus.core.production.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/** {@code production.production_batch}. */
@Entity
@Table(schema = "production", name = "production_batch")
public class ProductionBatch {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(nullable = false)
  public String competence;

  @Column(nullable = false)
  public String cnes;

  @Column(nullable = false)
  public String kind;

  @Column(nullable = false)
  public String status;

  @Column(name = "records_count", nullable = false)
  public int recordsCount;

  @Column(name = "total_quantity", nullable = false)
  public int totalQuantity;

  @Column(name = "estimated_value", nullable = false)
  public BigDecimal estimatedValue = BigDecimal.ZERO;

  @Column(name = "created_by")
  public String createdBy;

  @Column(name = "approved_by")
  public String approvedBy;

  @Column(name = "approved_at")
  public Instant approvedAt;

  @Column(name = "approval_justification")
  public String approvalJustification;

  @Column(name = "export_layout")
  public String exportLayout;

  @Column(name = "export_file_ref")
  public String exportFileRef;

  @Column(name = "export_sha256")
  public String exportSha256;

  @Column(name = "export_size_bytes")
  public Long exportSizeBytes;

  @Column(name = "export_lines")
  public Integer exportLines;

  @Column(name = "export_missing_ids")
  public Integer exportMissingIds;

  @Column(name = "exported_by")
  public String exportedBy;

  @Column(name = "exported_at")
  public Instant exportedAt;

  @Column(name = "protocol_number")
  public String protocolNumber;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Version public long version;
}
