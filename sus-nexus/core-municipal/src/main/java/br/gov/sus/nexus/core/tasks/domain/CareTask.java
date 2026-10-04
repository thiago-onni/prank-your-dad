package br.gov.sus.nexus.core.tasks.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Tarefa de cuidado/operação ({@code tasks.care_task}). */
@Entity
@Table(schema = "tasks", name = "care_task")
public class CareTask {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "task_type", nullable = false)
  public String taskType;

  @Column(nullable = false)
  public String status;

  @Column(nullable = false)
  public String priority;

  @Column(nullable = false)
  public String title;

  public String description;

  @Column(name = "citizen_id")
  public String citizenId;

  @Column(name = "assignee_kind")
  public String assigneeKind;

  @Column(name = "assignee_id")
  public String assigneeId;

  @Column(name = "due_at")
  public Instant dueAt;

  @Column(name = "sla_policy_id")
  public String slaPolicyId;

  @Column(name = "sla_breached_at")
  public Instant slaBreachedAt;

  @Column(name = "origin_kind")
  public String originKind;

  @Column(name = "origin_id")
  public String originId;

  @Column(name = "origin_version")
  public String originVersion;

  public String outcome;
  public String reason;

  @Column(name = "created_by")
  public String createdBy;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Column(name = "completed_at")
  public Instant completedAt;

  @Version public long version;
}
