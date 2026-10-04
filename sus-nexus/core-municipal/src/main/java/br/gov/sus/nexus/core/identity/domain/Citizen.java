package br.gov.sus.nexus.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;

/** Cidadão municipal (golden record corrente). Histórico em citizen_demographic_history. */
@Entity
@Table(schema = "identity", name = "citizen")
public class Citizen {

  public static final String STATUS_ACTIVE = "active";
  public static final String STATUS_MERGED = "merged";
  public static final String STATUS_INACTIVE = "inactive";

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(nullable = false)
  public String status = STATUS_ACTIVE;

  @Column(name = "merged_into_id")
  public String mergedIntoId;

  @Column(name = "registration_state", nullable = false)
  public String registrationState;

  @Column(name = "identity_confidence", nullable = false)
  public String identityConfidence = "confirmed";

  @Column(name = "legal_name", nullable = false)
  public String legalName;

  @Column(name = "social_name")
  public String socialName;

  @Column(name = "mother_name")
  public String motherName;

  @Column(name = "father_name")
  public String fatherName;

  @Column(nullable = false)
  public LocalDate birthdate;

  @Column(nullable = false)
  public String sex = "unknown";

  @Column(name = "race_color")
  public String raceColor;

  public String nationality;

  @Column(nullable = false)
  public boolean deceased;

  @Column(name = "deceased_at")
  public LocalDate deceasedAt;

  @Column(name = "normalized_name", nullable = false)
  public String normalizedName;

  @Column(name = "normalized_social_name")
  public String normalizedSocialName;

  @Column(name = "normalized_mother_name")
  public String normalizedMotherName;

  @Column(name = "health_unit_cnes")
  public String healthUnitCnes;

  @Column(name = "team_ine")
  public String teamIne;

  public String microarea;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Version public long version;

  public boolean isMerged() {
    return STATUS_MERGED.equals(status);
  }
}
