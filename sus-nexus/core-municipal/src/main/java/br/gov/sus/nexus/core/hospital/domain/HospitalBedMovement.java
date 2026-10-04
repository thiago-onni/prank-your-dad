package br.gov.sus.nexus.core.hospital.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code hospital.hospital_bed_movement} (append-only). */
@Entity
@Table(schema = "hospital", name = "hospital_bed_movement")
public class HospitalBedMovement {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "episode_id", nullable = false)
  public String episodeId;

  @Column(nullable = false)
  public String movement;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  @Column(name = "recorded_at", nullable = false)
  public Instant recordedAt = Instant.now();

  public String ward;
  public String bed;

  @Column(name = "previous_ward")
  public String previousWard;

  @Column(name = "previous_bed")
  public String previousBed;

  @Column(name = "attending_professional_id")
  public String attendingProfessionalId;

  public String reason;

  @Column(name = "source_system")
  public String sourceSystem;

  @Column(name = "actor_id")
  public String actorId;
}
