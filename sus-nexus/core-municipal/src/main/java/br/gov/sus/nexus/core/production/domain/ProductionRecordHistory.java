package br.gov.sus.nexus.core.production.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code production.production_record_history} (append-only). */
@Entity
@Immutable
@Table(schema = "production", name = "production_record_history")
public class ProductionRecordHistory {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "record_id", nullable = false)
  public String recordId;

  @Column(nullable = false)
  public String action;

  @Column(name = "from_status")
  public String fromStatus;

  @Column(name = "to_status")
  public String toStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  public Map<String, Object> changes = new LinkedHashMap<>();

  public String justification;

  @Column(name = "rule_version")
  public String ruleVersion;

  @Column(name = "actor_id")
  public String actorId;

  @Column(name = "actor_kind")
  public String actorKind;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt = Instant.now();
}
