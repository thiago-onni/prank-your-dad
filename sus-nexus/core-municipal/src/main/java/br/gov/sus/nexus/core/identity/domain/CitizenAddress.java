package br.gov.sus.nexus.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Endereço do cidadão. */
@Entity
@Table(schema = "identity", name = "citizen_address")
public class CitizenAddress {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  public String street;
  public String number;
  public String complement;
  public String district;

  @Column(name = "city_ibge")
  public String cityIbge;

  @Column(name = "postal_code")
  public String postalCode;

  @Column(name = "is_current", nullable = false)
  public boolean current = true;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "received_at", nullable = false)
  public Instant receivedAt = Instant.now();
}
