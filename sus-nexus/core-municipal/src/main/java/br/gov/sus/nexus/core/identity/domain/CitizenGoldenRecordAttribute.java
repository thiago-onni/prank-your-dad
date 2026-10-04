package br.gov.sus.nexus.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Proveniência por atributo do golden record (sobrevivência por atributo). */
@Entity
@Table(schema = "identity", name = "citizen_golden_record_attribute")
public class CitizenGoldenRecordAttribute {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(nullable = false)
  public String attribute;

  public String value;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "source_record_id")
  public String sourceRecordId;

  @Column(name = "received_at", nullable = false)
  public Instant receivedAt = Instant.now();

  @Column(nullable = false)
  public double confidence = 1.0;
}
