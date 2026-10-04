package br.gov.sus.nexus.core.hospital.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Vínculo (tenant, sistema de origem, id de origem) → episódio. */
@Entity
@Table(schema = "hospital", name = "hospital_episode_source_link")
public class HospitalEpisodeSourceLink {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "episode_id", nullable = false)
  public String episodeId;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  public String connector;

  @Column(name = "source_record_id", nullable = false)
  public String sourceRecordId;

  @Column(name = "source_record_version")
  public String sourceRecordVersion;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();
}
