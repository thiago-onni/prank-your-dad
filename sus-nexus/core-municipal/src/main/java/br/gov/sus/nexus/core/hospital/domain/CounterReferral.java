package br.gov.sus.nexus.core.hospital.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code hospital.counter_referral}. */
@Entity
@Table(schema = "hospital", name = "counter_referral")
public class CounterReferral {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "episode_id", nullable = false)
  public String episodeId;

  @Column(name = "received_at", nullable = false)
  public Instant receivedAt;

  @Column(name = "target_health_unit_cnes")
  public String targetHealthUnitCnes;

  @Column(name = "document_ref")
  public String documentRef;

  @Column(name = "document_sha256")
  public String documentSha256;

  @Column(name = "recommendations_count")
  public Integer recommendationsCount;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "source_record_id")
  public String sourceRecordId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
