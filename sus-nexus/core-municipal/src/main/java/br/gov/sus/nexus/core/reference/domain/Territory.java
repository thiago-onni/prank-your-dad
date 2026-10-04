package br.gov.sus.nexus.core.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Território de uma unidade de saúde. */
@Entity
@Table(schema = "reference", name = "territory")
public class Territory {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "health_unit_id", nullable = false)
  public String healthUnitId;

  @Column(nullable = false)
  public String name;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
