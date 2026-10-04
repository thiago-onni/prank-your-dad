package br.gov.sus.nexus.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Vínculo cidadão × registro do sistema de origem (sistema, id, versão). */
@Entity
@Table(schema = "identity", name = "citizen_source_link")
public class CitizenSourceLink {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(nullable = false)
  public String connector;

  @Column(name = "source_record_id", nullable = false)
  public String sourceRecordId;

  @Column(name = "source_record_version")
  public String sourceRecordVersion;

  public String cnes;

  @Column(name = "received_at", nullable = false)
  public Instant receivedAt = Instant.now();
}
