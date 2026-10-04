package br.gov.sus.nexus.core.exams.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Pedido de exame ({@code exams.exam_order}). */
@Entity
@Table(schema = "exams", name = "exam_order")
public class ExamOrder {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(nullable = false)
  public String status;

  @Column(name = "requested_at", nullable = false)
  public Instant requestedAt;

  @Column(name = "exam_code", nullable = false)
  public String examCode;

  @Column(name = "code_system", nullable = false)
  public String codeSystem;

  @Column(name = "exam_description")
  public String examDescription;

  public String category;
  public String priority;

  @Column(name = "requesting_cnes")
  public String requestingCnes;

  @Column(name = "requesting_professional_id")
  public String requestingProfessionalId;

  @Column(name = "performer_cnes")
  public String performerCnes;

  @Column(name = "regulation_request_id")
  public String regulationRequestId;

  @Column(name = "appointment_id")
  public String appointmentId;

  @Column(name = "scheduled_at")
  public Instant scheduledAt;

  @Column(name = "performed_at")
  public Instant performedAt;

  @Column(name = "reported_at")
  public Instant reportedAt;

  @Column(name = "care_line")
  public String careLine;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "text[]")
  public String[] issues = new String[0];

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
