package br.gov.sus.nexus.core.exams.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Vínculo (tenant, sistema de origem, id de origem) → pedido de exame. */
@Entity
@Table(schema = "exams", name = "exam_order_source_link")
public class ExamOrderSourceLink {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "order_id", nullable = false)
  public String orderId;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  public String connector;

  @Column(name = "source_record_id", nullable = false)
  public String sourceRecordId;

  @Column(name = "source_record_version")
  public String sourceRecordVersion;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();
}
