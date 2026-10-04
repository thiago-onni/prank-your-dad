package br.gov.sus.nexus.core.regulation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Histórico append-only de status da solicitação. */
@Entity
@Table(schema = "regulation", name = "regulation_status_history")
public class RegulationStatusHistory {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "request_id", nullable = false)
  public String requestId;

  @Column(nullable = false)
  public String status;

  @Column(name = "previous_status")
  public String previousStatus;

  public String reason;

  @Column(name = "actor_kind", nullable = false)
  public String actorKind;

  @Column(name = "actor_id")
  public String actorId;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  @Column(name = "recorded_at", nullable = false)
  public Instant recordedAt = Instant.now();

  @Column(name = "source_system")
  public String sourceSystem;
}
