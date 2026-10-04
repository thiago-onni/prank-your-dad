package br.gov.sus.nexus.core.careplan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code careplan.care_gap}. */
@Entity
@Table(schema = "careplan", name = "care_gap")
public class CareGap {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(name = "care_plan_id")
  public String carePlanId;

  @Column(name = "item_id")
  public String itemId;

  @Column(name = "care_line", nullable = false)
  public String careLine;

  @Column(name = "gap_kind", nullable = false)
  public String gapKind;

  @Column(nullable = false)
  public String status;

  @Column(name = "expected_by")
  public Instant expectedBy;

  @Column(name = "protocol_id")
  public String protocolId;

  @Column(name = "protocol_version", nullable = false)
  public String protocolVersion;

  @Column(name = "health_unit_cnes")
  public String healthUnitCnes;

  @Column(name = "team_ine")
  public String teamIne;

  public String microarea;

  @Column(name = "task_id")
  public String taskId;

  @Column(name = "origin_ref")
  public String originRef;

  @Column(name = "detected_at", nullable = false)
  public Instant detectedAt;

  @Column(name = "resolved_at")
  public Instant resolvedAt;

  public String resolution;

  public String note;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();
}
