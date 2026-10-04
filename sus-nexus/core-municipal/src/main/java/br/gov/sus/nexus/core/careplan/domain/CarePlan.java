package br.gov.sus.nexus.core.careplan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** {@code careplan.care_plan}. */
@Entity
@Table(schema = "careplan", name = "care_plan")
public class CarePlan {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(name = "care_line", nullable = false)
  public String careLine;

  @Column(nullable = false)
  public String status;

  @Column(name = "protocol_id", nullable = false)
  public String protocolId;

  @Column(name = "protocol_version_id", nullable = false)
  public String protocolVersionId;

  @Column(name = "protocol_version", nullable = false)
  public String protocolVersion;

  @Column(name = "health_unit_cnes")
  public String healthUnitCnes;

  @Column(name = "team_ine")
  public String teamIne;

  public String microarea;

  @Column(name = "responsible_professional_id")
  public String responsibleProfessionalId;

  @Column(name = "origin_kind")
  public String originKind;

  @Column(name = "origin_id")
  public String originId;

  @Column(name = "start_at", nullable = false)
  public Instant startAt;

  @Column(name = "last_care_event_at")
  public Instant lastCareEventAt;

  @Column(name = "closed_reason")
  public String closedReason;

  @Column(name = "closed_at")
  public Instant closedAt;

  @Column(name = "created_by")
  public String createdBy;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Version public long version;
}
