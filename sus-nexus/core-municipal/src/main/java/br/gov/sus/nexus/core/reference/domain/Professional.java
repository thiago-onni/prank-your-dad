package br.gov.sus.nexus.core.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Profissional de saúde (identificadores somente hash/mascarados). */
@Entity
@Table(schema = "reference", name = "professional")
public class Professional {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(nullable = false)
  public String name;

  @Column(name = "cns_hash")
  public String cnsHash;

  @Column(name = "cns_masked")
  public String cnsMasked;

  @Column(name = "cpf_hash")
  public String cpfHash;

  @Column(name = "cpf_masked")
  public String cpfMasked;

  @Column(nullable = false)
  public boolean active = true;

  @Column(name = "source_system")
  public String sourceSystem;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Version public long version;
}
