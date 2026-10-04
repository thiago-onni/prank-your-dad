package br.gov.sus.nexus.core.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Microárea de um território, opcionalmente vinculada a uma equipe. */
@Entity
@Table(schema = "reference", name = "microarea")
public class Microarea {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "territory_id", nullable = false)
  public String territoryId;

  @Column(name = "care_team_id")
  public String careTeamId;

  @Column(nullable = false)
  public String code;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
