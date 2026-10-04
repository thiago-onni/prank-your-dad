package br.gov.sus.nexus.core.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Dead letter aberto por um conector (triagem pelo operador de integração). */
@Entity
@Table(schema = "integration", name = "dead_letter")
public class DeadLetter {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "message_id", nullable = false)
  public String messageId;

  @Column(name = "connector_id", nullable = false)
  public String connectorId;

  public String topic;

  @Column(nullable = false)
  public String reason;

  public String stage;

  @Column(nullable = false)
  public int attempts;

  public String owner;

  @Column(name = "payload_ref")
  public String payloadRef;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "triaged_at")
  public Instant triagedAt;

  @Column(name = "triaged_by")
  public String triagedBy;
}
