package br.gov.sus.nexus.core.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Agendamento da agenda consolidada ({@code scheduling.appointment}). */
@Entity
@Table(schema = "scheduling", name = "appointment")
public class Appointment {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(nullable = false)
  public String status;

  @Column(nullable = false)
  public String kind;

  @Column(name = "service_code")
  public String serviceCode;

  @Column(name = "code_system")
  public String codeSystem;

  @Column(name = "health_unit_cnes")
  public String healthUnitCnes;

  @Column(name = "professional_id")
  public String professionalId;

  @Column(name = "scheduled_start", nullable = false)
  public Instant scheduledStart;

  @Column(name = "scheduled_end")
  public Instant scheduledEnd;

  @Column(name = "regulation_request_id")
  public String regulationRequestId;

  @Column(name = "exam_order_id")
  public String examOrderId;

  @Column(name = "care_line")
  public String careLine;

  @Column(name = "cancellation_reason")
  public String cancellationReason;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "source_record_id", nullable = false)
  public String sourceRecordId;

  @Column(name = "source_record_version")
  public String sourceRecordVersion;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Version public long version;
}
