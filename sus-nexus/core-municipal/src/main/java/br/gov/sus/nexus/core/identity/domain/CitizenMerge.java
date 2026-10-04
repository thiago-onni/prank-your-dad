package br.gov.sus.nexus.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Fusão efetivada (reversível): guarda snapshot pré-fusão para unmerge. */
@Entity
@Table(schema = "identity", name = "citizen_merge")
public class CitizenMerge {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "case_id", nullable = false)
  public String caseId;

  @Column(name = "surviving_citizen_id", nullable = false)
  public String survivingCitizenId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "merged_citizen_ids", nullable = false)
  public List<String> mergedCitizenIds;

  /** {@code { citizenId: { status, merged_into_id, registration_state, identity_confidence } }}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  public Map<String, Object> snapshot;

  @Column(nullable = false)
  public String reason;

  @Column(name = "decided_by", nullable = false)
  public String decidedBy;

  @Column(name = "merged_at", nullable = false)
  public Instant mergedAt = Instant.now();

  @Column(name = "unmerged_at")
  public Instant unmergedAt;

  @Column(name = "unmerged_by")
  public String unmergedBy;

  @Column(name = "unmerge_reason")
  public String unmergeReason;
}
