package br.gov.sus.nexus.core.careplan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code careplan.care_plan_item}. */
@Entity
@Table(schema = "careplan", name = "care_plan_item")
public class CarePlanItem {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "care_plan_id", nullable = false)
  public String carePlanId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(nullable = false)
  public int sequence;

  @Column(nullable = false)
  public String kind;

  @Column(nullable = false)
  public String title;

  public String code;

  @Column(name = "code_system")
  public String codeSystem;

  @Column(name = "expected_by")
  public Instant expectedBy;

  @Column(name = "periodicity_days")
  public Integer periodicityDays;

  @Column(name = "gap_after_days", nullable = false)
  public int gapAfterDays;

  @Column(nullable = false)
  public String priority = "medium";

  @Column(nullable = false)
  public String status;

  @Column(name = "performed_at")
  public Instant performedAt;

  @Column(name = "evidence_ref")
  public String evidenceRef;

  public String note;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  public boolean isOpen() {
    return "planned".equals(status) || "scheduled".equals(status) || "missed".equals(status);
  }
}
