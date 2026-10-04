package br.gov.sus.nexus.core.hospital.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code hospital.hospital_episode}. */
@Entity
@Table(schema = "hospital", name = "hospital_episode")
public class HospitalEpisode {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(name = "hospital_cnes", nullable = false)
  public String hospitalCnes;

  @Column(name = "episode_class", nullable = false)
  public String episodeClass;

  @Column(nullable = false)
  public String status;

  @Column(name = "admitted_at", nullable = false)
  public Instant admittedAt;

  @Column(name = "discharged_at")
  public Instant dischargedAt;

  @Column(name = "length_of_stay_days")
  public Integer lengthOfStayDays;

  public String disposition;
  public String ward;
  public String bed;

  @Column(name = "attending_professional_id")
  public String attendingProfessionalId;

  @Column(name = "admission_source")
  public String admissionSource;

  @Column(name = "regulation_request_id")
  public String regulationRequestId;

  @Column(name = "principal_diagnosis_cid")
  public String principalDiagnosisCid;

  @Column(name = "cid_highly_restricted", nullable = false)
  public boolean cidHighlyRestricted;

  @Column(name = "aih_number")
  public String aihNumber;

  @Column(name = "procedures_count")
  public Integer proceduresCount;

  @Column(name = "readmission_within_30d", nullable = false)
  public boolean readmissionWithin30d;

  @Column(name = "previous_episode_id")
  public String previousEpisodeId;

  @Column(name = "reference_health_unit_cnes")
  public String referenceHealthUnitCnes;

  @Column(name = "reference_team_ine")
  public String referenceTeamIne;

  @Column(name = "reference_microarea")
  public String referenceMicroarea;

  @Column(name = "risk_level")
  public String riskLevel;

  @Column(name = "risk_rule_version")
  public String riskRuleVersion;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "care_lines", nullable = false, columnDefinition = "text[]")
  public String[] careLines = new String[0];

  @Column(name = "followup_plan_present")
  public Boolean followupPlanPresent;

  @Column(name = "followup_due_days")
  public Integer followupDueDays;

  @Column(name = "followup_status")
  public String followupStatus;

  @Column(name = "followup_task_id")
  public String followupTaskId;

  @Column(name = "followup_due_at")
  public Instant followupDueAt;

  @Column(name = "followup_outcome")
  public String followupOutcome;

  @Column(name = "followup_contacted_at")
  public Instant followupContactedAt;

  @Column(name = "followup_care_plan_id")
  public String followupCarePlanId;

  @Column(name = "summary_document_ref")
  public String summaryDocumentRef;

  @Column(name = "summary_document_sha256")
  public String summaryDocumentSha256;

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
