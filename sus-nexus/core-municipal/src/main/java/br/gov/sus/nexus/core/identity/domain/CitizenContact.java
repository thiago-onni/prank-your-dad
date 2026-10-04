package br.gov.sus.nexus.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Contato (telefone/celular/e-mail) do cidadão. */
@Entity
@Table(schema = "identity", name = "citizen_contact")
public class CitizenContact {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(nullable = false)
  public String kind;

  @Column(nullable = false)
  public String value;

  @Column(name = "value_norm", nullable = false)
  public String valueNorm;

  @Column(name = "value_masked", nullable = false)
  public String valueMasked;

  @Column(nullable = false)
  public boolean preferred;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "received_at", nullable = false)
  public Instant receivedAt = Instant.now();
}
