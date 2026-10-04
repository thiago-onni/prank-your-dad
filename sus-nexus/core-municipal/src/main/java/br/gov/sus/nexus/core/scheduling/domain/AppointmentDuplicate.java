package br.gov.sus.nexus.core.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Duplicidade detectada (AGE-004): mesmo cidadão + mesmo serviço em janela configurável. */
@Entity
@Table(schema = "scheduling", name = "appointment_duplicate")
public class AppointmentDuplicate {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(name = "service_code")
  public String serviceCode;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "appointment_ids", nullable = false)
  public List<String> appointmentIds;

  @Column(name = "window_hours", nullable = false)
  public int windowHours;

  @Column(name = "detected_at", nullable = false)
  public Instant detectedAt = Instant.now();

  @Column(name = "resolved_at")
  public Instant resolvedAt;

  public String resolution;
}
