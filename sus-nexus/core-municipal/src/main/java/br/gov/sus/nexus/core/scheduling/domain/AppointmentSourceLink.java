package br.gov.sus.nexus.core.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Vínculo (tenant, sistema de origem, id de origem) → agendamento. */
@Entity
@Table(schema = "scheduling", name = "appointment_source_link")
public class AppointmentSourceLink {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "appointment_id", nullable = false)
  public String appointmentId;

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
