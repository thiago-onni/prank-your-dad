package br.gov.sus.nexus.core.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Estabelecimento de saúde (CNES). */
@Entity
@Table(schema = "reference", name = "health_unit")
public class HealthUnit {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "organization_id")
  public String organizationId;

  @Column(nullable = false)
  public String cnes;

  @Column(nullable = false)
  public String name;

  @Column(name = "kind_code")
  public String kindCode;

  @Column(name = "kind_description")
  public String kindDescription;

  public String address;

  @Column(name = "city_ibge")
  public String cityIbge;

  public String competence;

  @JdbcTypeCode(SqlTypes.JSON)
  public Map<String, Object> attributes;

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
