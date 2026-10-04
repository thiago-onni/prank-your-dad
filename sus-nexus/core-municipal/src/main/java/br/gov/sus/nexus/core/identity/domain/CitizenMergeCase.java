package br.gov.sus.nexus.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Caso de revisão de duplicidade/conflito. */
@Entity
@Table(schema = "identity", name = "citizen_merge_case")
public class CitizenMergeCase {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(nullable = false)
  public String status = "open";

  public String reason;

  @Column(nullable = false)
  public String classification;

  public Double score;

  @Column(name = "rule_version")
  public String ruleVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "candidate_ids", nullable = false)
  public List<String> candidateIds;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  public List<String> conflicts = List.of();

  @Column(name = "opened_at", nullable = false)
  public Instant openedAt = Instant.now();

  @Column(name = "decided_at")
  public Instant decidedAt;

  @Column(name = "decided_by")
  public String decidedBy;

  @Column(name = "decision_reason")
  public String decisionReason;

  @Column(name = "merge_id")
  public String mergeId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Version public long version;

  public boolean isOpen() {
    return "open".equals(status) || "in_review".equals(status);
  }
}
