package br.gov.sus.nexus.core.hospital.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code hospital.hospital_discharge}: metadados da alta e fatos usados pela regra de risco. */
@Entity
@Table(schema = "hospital", name = "hospital_discharge")
public class HospitalDischarge {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "episode_id", nullable = false)
  public String episodeId;

  @Column(name = "discharged_at", nullable = false)
  public Instant dischargedAt;

  @Column(nullable = false)
  public String disposition;

  @Column(name = "length_of_stay_days", nullable = false)
  public int lengthOfStayDays;

  @Column(name = "procedures_count")
  public Integer proceduresCount;

  @Column(name = "followup_plan_present")
  public Boolean followupPlanPresent;

  @Column(name = "followup_due_days")
  public Integer followupDueDays;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "care_lines", nullable = false, columnDefinition = "text[]")
  public String[] careLines = new String[0];

  @Column(name = "readmission_within_30d", nullable = false)
  public boolean readmissionWithin30d;

  @Column(name = "risk_level", nullable = false)
  public String riskLevel;

  @Column(name = "risk_rule_version", nullable = false)
  public String riskRuleVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "risk_facts", nullable = false)
  public Map<String, Object> riskFacts = Map.of();

  @Column(name = "summary_document_ref")
  public String summaryDocumentRef;

  @Column(name = "summary_document_sha256")
  public String summaryDocumentSha256;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "source_record_id")
  public String sourceRecordId;

  @Column(name = "actor_id")
  public String actorId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
