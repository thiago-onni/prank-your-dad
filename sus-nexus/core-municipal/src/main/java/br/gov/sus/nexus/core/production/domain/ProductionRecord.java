package br.gov.sus.nexus.core.production.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code production.production_record}. CNS/CPF só como hash, máscara e cifra. */
@Entity
@Table(schema = "production", name = "production_record")
public class ProductionRecord {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(nullable = false)
  public String kind;

  @Column(nullable = false)
  public String competence;

  @Column(nullable = false)
  public String cnes;

  @Column(name = "professional_cns_hash")
  public String professionalCnsHash;

  @Column(name = "professional_cns_masked")
  public String professionalCnsMasked;

  @Column(name = "professional_cns_enc")
  public byte[] professionalCnsEnc;

  @Column(name = "professional_cbo", nullable = false)
  public String professionalCbo;

  @Column(name = "procedure_code", nullable = false)
  public String procedureCode;

  @Column(nullable = false)
  public int quantity;

  @Column(name = "citizen_id")
  public String citizenId;

  @Column(name = "citizen_identifier_system")
  public String citizenIdentifierSystem;

  @Column(name = "citizen_identifier_hash")
  public String citizenIdentifierHash;

  @Column(name = "citizen_identifier_masked")
  public String citizenIdentifierMasked;

  @Column(name = "citizen_identifier_enc")
  public byte[] citizenIdentifierEnc;

  @Column(name = "cid_code")
  public String cidCode;

  @Column(name = "attendance_date", nullable = false)
  public LocalDate attendanceDate;

  @Column(name = "character_of_care")
  public String characterOfCare;

  @Column(name = "apac_number")
  public String apacNumber;

  @Column(name = "aih_number")
  public String aihNumber;

  @Column(name = "encounter_source_system")
  public String encounterSourceSystem;

  @Column(name = "encounter_source_record_id")
  public String encounterSourceRecordId;

  @Column(name = "appointment_source_system")
  public String appointmentSourceSystem;

  @Column(name = "appointment_source_record_id")
  public String appointmentSourceRecordId;

  @Column(name = "appointment_id")
  public String appointmentId;

  @Column(name = "hospital_episode_source_system")
  public String hospitalEpisodeSourceSystem;

  @Column(name = "hospital_episode_source_record_id")
  public String hospitalEpisodeSourceRecordId;

  @Column(name = "hospital_episode_id")
  public String hospitalEpisodeId;

  @Column(nullable = false)
  public String status;

  @Column(name = "rule_version")
  public String ruleVersion;

  @Column(name = "validated_at")
  public Instant validatedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  public Map<String, Object> facts = new LinkedHashMap<>();

  @Column(name = "unit_value")
  public BigDecimal unitValue;

  @Column(name = "estimated_value")
  public BigDecimal estimatedValue;

  @Column(name = "paid_amount")
  public BigDecimal paidAmount;

  @Column(name = "approved_quantity")
  public Integer approvedQuantity;

  @Column(name = "outcome_reason_code")
  public String outcomeReasonCode;

  @Column(name = "outcome_reason")
  public String outcomeReason;

  @Column(name = "batch_id")
  public String batchId;

  @Column(name = "exported_at")
  public Instant exportedAt;

  @Column(name = "deadline_at")
  public Instant deadlineAt;

  @Column(name = "correction_count", nullable = false)
  public int correctionCount;

  @Column(name = "last_corrected_at")
  public Instant lastCorrectedAt;

  @Column(name = "payload_hash")
  public String payloadHash;

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
