package br.gov.sus.nexus.core.consent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code consent.consent}. */
@Entity
@Table(schema = "consent", name = "consent")
public class Consent {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(nullable = false)
  public String purpose;

  @Column(nullable = false)
  public String status;

  public String channel;

  @Column(name = "recorded_at", nullable = false)
  public Instant recordedAt;

  @Column(nullable = false)
  public String source;

  @Column(name = "actor_id")
  public String actorId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
