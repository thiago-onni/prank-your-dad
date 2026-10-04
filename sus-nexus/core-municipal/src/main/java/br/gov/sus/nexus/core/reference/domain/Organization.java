package br.gov.sus.nexus.core.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Organização (secretaria, consórcio, prestador). */
@Entity
@Table(schema = "reference", name = "organization")
public class Organization {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(nullable = false)
  public String name;

  @Column(nullable = false)
  public String kind = "health_secretariat";

  public String cnpj;

  @Column(nullable = false)
  public boolean active = true;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Version public long version;
}
