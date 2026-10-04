package br.gov.sus.nexus.core.regulation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Decisão do regulador registrada a partir do sistema oficial (append-only; REG-003). */
@Entity
@Table(schema = "regulation", name = "regulation_decision")
public class RegulationDecision {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "request_id", nullable = false)
  public String requestId;

  @Column(name = "regulator_id")
  public String regulatorId;

  @Column(nullable = false)
  public String decision;

  public String reason;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  @Column(name = "recorded_at", nullable = false)
  public Instant recordedAt = Instant.now();

  @Column(name = "source_system")
  public String sourceSystem;
}
