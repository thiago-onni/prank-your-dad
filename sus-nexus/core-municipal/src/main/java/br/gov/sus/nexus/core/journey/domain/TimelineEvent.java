package br.gov.sus.nexus.core.journey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Read model {@code journey.timeline_event}. */
@Entity
@Table(schema = "journey", name = "timeline_event")
public class TimelineEvent {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(name = "original_citizen_id", nullable = false)
  public String originalCitizenId;

  @Column(nullable = false)
  public String domain;

  @Column(name = "event_type", nullable = false)
  public String eventType;

  @Column(name = "event_id", nullable = false)
  public String eventId;

  @Column(name = "aggregate_id")
  public String aggregateId;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  @Column(name = "recorded_at", nullable = false)
  public Instant recordedAt = Instant.now();

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  public String cnes;

  @Column(name = "health_unit_name")
  public String healthUnitName;

  @Column(name = "professional_ref")
  public String professionalRef;

  @Column(nullable = false)
  public String status;

  @Column(nullable = false)
  public String confidence = "confirmed";

  @Column(nullable = false)
  public String sensitivity = "internal";

  public String summary;

  @Column(name = "detail_ref")
  public String detailRef;

  @Column(name = "care_line")
  public String careLine;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "correlation_chain", nullable = false, columnDefinition = "text[]")
  public String[] correlationChain = new String[0];
}
