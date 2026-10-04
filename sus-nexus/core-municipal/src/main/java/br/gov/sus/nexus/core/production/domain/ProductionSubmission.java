package br.gov.sus.nexus.core.production.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code production.production_submission}: exportação ou transmissão (confirmada) do lote. */
@Entity
@Table(schema = "production", name = "production_submission")
public class ProductionSubmission {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "batch_id", nullable = false)
  public String batchId;

  @Column(nullable = false)
  public String kind;

  public String layout;

  @Column(name = "file_ref")
  public String fileRef;

  public String sha256;

  @Column(name = "size_bytes")
  public Long sizeBytes;

  public Integer lines;

  @Column(name = "protocol_number")
  public String protocolNumber;

  @Column(name = "actor_id")
  public String actorId;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt = Instant.now();
}
