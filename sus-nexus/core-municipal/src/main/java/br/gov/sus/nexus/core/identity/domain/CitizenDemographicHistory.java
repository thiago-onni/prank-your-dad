package br.gov.sus.nexus.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Histórico demográfico bitemporal simplificado (valid_* = fato; recorded_* = registro). */
@Entity
@Table(schema = "identity", name = "citizen_demographic_history")
public class CitizenDemographicHistory {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  public Map<String, Object> attributes;

  @Column(name = "valid_from", nullable = false)
  public Instant validFrom = Instant.now();

  @Column(name = "valid_to")
  public Instant validTo;

  @Column(name = "recorded_from", nullable = false)
  public Instant recordedFrom = Instant.now();

  @Column(name = "recorded_to")
  public Instant recordedTo;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "source_record_id")
  public String sourceRecordId;

  @Column(name = "change_reason")
  public String changeReason;
}
