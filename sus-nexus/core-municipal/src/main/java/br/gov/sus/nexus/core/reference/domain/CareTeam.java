package br.gov.sus.nexus.core.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Equipe de saúde (INE). */
@Entity
@Table(schema = "reference", name = "care_team")
public class CareTeam {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "health_unit_id", nullable = false)
  public String healthUnitId;

  @Column(nullable = false)
  public String ine;

  public String name;

  @Column(name = "team_type")
  public String teamType;

  @Column(nullable = false)
  public boolean active = true;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();
}
