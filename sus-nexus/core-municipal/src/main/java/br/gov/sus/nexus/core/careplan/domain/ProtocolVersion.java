package br.gov.sus.nexus.core.careplan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code careplan.protocol_version}: definição versionada (jsonb) com ciclo de aprovação. */
@Entity
@Table(schema = "careplan", name = "protocol_version")
public class ProtocolVersion {

  @Id public String id;

  @Column(name = "tenant_id")
  public String tenantId;

  @Column(name = "protocol_id", nullable = false)
  public String protocolId;

  @Column(nullable = false)
  public String version;

  @Column(nullable = false)
  public String status;

  public String description;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  public Map<String, Object> eligibility = Map.of();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  public List<Map<String, Object>> items = List.of();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "test_cases", nullable = false)
  public List<Map<String, Object>> testCases = List.of();

  @Column(name = "lost_to_followup_days", nullable = false)
  public int lostToFollowupDays = 90;

  @Column(name = "approved_by")
  public String approvedBy;

  @Column(name = "approved_at")
  public Instant approvedAt;

  @Column(name = "effective_from")
  public Instant effectiveFrom;

  @Column(name = "created_by")
  public String createdBy;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();
}
