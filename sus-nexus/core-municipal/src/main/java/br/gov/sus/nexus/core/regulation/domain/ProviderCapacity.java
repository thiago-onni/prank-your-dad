package br.gov.sus.nexus.core.regulation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Oferta/capacidade por prestador, serviço e competência (REG-006). */
@Entity
@Table(schema = "regulation", name = "provider_capacity")
public class ProviderCapacity {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "provider_cnes", nullable = false)
  public String providerCnes;

  @Column(name = "service_code", nullable = false)
  public String serviceCode;

  @Column(name = "code_system", nullable = false)
  public String codeSystem = "SIGTAP";

  @Column(nullable = false)
  public String competence;

  @Column(nullable = false)
  public int offered;

  @Column(nullable = false)
  public int used;

  @Column(nullable = false)
  public int available;

  @Column(name = "source_system")
  public String sourceSystem;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();
}
