package br.gov.sus.nexus.core.production.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code production.production_validation_issue}: pendência com regra e versão aplicadas. */
@Entity
@Table(schema = "production", name = "production_validation_issue")
public class ProductionValidationIssue {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "record_id", nullable = false)
  public String recordId;

  @Column(name = "rule_id", nullable = false)
  public String ruleId;

  @Column(name = "rule_version", nullable = false)
  public String ruleVersion;

  @Column(nullable = false)
  public String severity;

  public String field;

  @Column(nullable = false)
  public String message;

  @Column(nullable = false)
  public String status;

  @Column(nullable = false)
  public String origin = "rule";

  @Column(name = "task_id")
  public String taskId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "resolved_at")
  public Instant resolvedAt;

  @Column(name = "resolved_by")
  public String resolvedBy;

  @Column(name = "resolution_note")
  public String resolutionNote;

  public boolean open() {
    return "open".equals(status);
  }

  public boolean error() {
    return "error".equals(severity);
  }
}
