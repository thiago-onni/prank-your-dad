package br.gov.sus.nexus.core.consent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code consent.communication_preference}: canal + valor mascarado/hash. */
@Entity
@Table(schema = "consent", name = "communication_preference")
public class CommunicationPreference {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(nullable = false)
  public String channel;

  @Column(name = "value_masked")
  public String valueMasked;

  @Column(name = "value_hash")
  public String valueHash;

  @Column(nullable = false)
  public boolean preferred;

  @Column(nullable = false)
  public boolean allowed = true;

  @Column(nullable = false)
  public String source;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
