package br.gov.sus.nexus.core.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Vínculo profissional × unidade × CBO (× equipe). */
@Entity
@Table(schema = "reference", name = "professional_role")
public class ProfessionalRole {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "professional_id", nullable = false)
  public String professionalId;

  @Column(name = "health_unit_id", nullable = false)
  public String healthUnitId;

  @Column(name = "care_team_id")
  public String careTeamId;

  @Column(nullable = false)
  public String cbo;

  @Column(nullable = false)
  public boolean active = true;

  @Column(name = "valid_from", nullable = false)
  public Instant validFrom = Instant.now();

  @Column(name = "valid_to")
  public Instant validTo;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
