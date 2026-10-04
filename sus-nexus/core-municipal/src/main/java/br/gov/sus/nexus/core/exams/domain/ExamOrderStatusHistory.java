package br.gov.sus.nexus.core.exams.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Histórico append-only de status do pedido de exame. */
@Entity
@Table(schema = "exams", name = "exam_order_status_history")
public class ExamOrderStatusHistory {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "order_id", nullable = false)
  public String orderId;

  @Column(nullable = false)
  public String status;

  @Column(name = "previous_status")
  public String previousStatus;

  public String reason;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  @Column(name = "recorded_at", nullable = false)
  public Instant recordedAt = Instant.now();

  @Column(name = "source_system")
  public String sourceSystem;

  @Column(name = "actor_id")
  public String actorId;
}
