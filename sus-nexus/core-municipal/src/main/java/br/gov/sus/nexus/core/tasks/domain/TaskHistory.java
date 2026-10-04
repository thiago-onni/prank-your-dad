package br.gov.sus.nexus.core.tasks.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Histórico append-only de transições da tarefa. */
@Entity
@Table(schema = "tasks", name = "task_history")
public class TaskHistory {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "task_id", nullable = false)
  public String taskId;

  @Column(nullable = false)
  public String action;

  @Column(name = "previous_status")
  public String previousStatus;

  @Column(nullable = false)
  public String status;

  @Column(name = "assignee_kind")
  public String assigneeKind;

  @Column(name = "assignee_id")
  public String assigneeId;

  public String outcome;
  public String reason;

  @Column(name = "actor_id", nullable = false)
  public String actorId;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt = Instant.now();
}
