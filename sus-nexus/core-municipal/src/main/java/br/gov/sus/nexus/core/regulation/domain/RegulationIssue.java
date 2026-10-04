package br.gov.sus.nexus.core.regulation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Pendência documental/administrativa (REG-005) ({@code regulation.regulation_issue}). */
@Entity
@Table(schema = "regulation", name = "regulation_issue")
public class RegulationIssue {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "request_id", nullable = false)
  public String requestId;

  @Column(nullable = false)
  public String kind;

  @Column(nullable = false)
  public String status = "open";

  public String description;

  @Column(name = "origin_kind")
  public String originKind;

  @Column(name = "origin_id")
  public String originId;

  @Column(name = "origin_version")
  public String originVersion;

  @Column(name = "created_by")
  public String createdBy;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "resolved_at")
  public Instant resolvedAt;

  public String resolution;
}
