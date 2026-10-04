package br.gov.sus.nexus.core.careplan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code careplan.protocol} (identidade do protocolo; versões em {@link ProtocolVersion}). */
@Entity
@Table(schema = "careplan", name = "protocol")
public class Protocol {

  @Id public String id;

  @Column(name = "tenant_id")
  public String tenantId;

  @Column(name = "care_line", nullable = false)
  public String careLine;

  @Column(nullable = false)
  public String name;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
