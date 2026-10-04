package br.gov.sus.nexus.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Identificador do cidadão: hash (busca), cifrado (reveal) e mascarado (exibição). */
@Entity
@Table(schema = "identity", name = "citizen_identifier")
public class CitizenIdentifier {

  public static final String STATUS_ACTIVE = "active";
  public static final String STATUS_DEPRECATED = "deprecated";
  public static final String STATUS_INVALID = "invalid";

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(nullable = false)
  public String system;

  @Column(name = "value_hash", nullable = false)
  public String valueHash;

  @Column(name = "value_enc", nullable = false)
  public byte[] valueEnc;

  @Column(name = "value_masked", nullable = false)
  public String valueMasked;

  @Column(nullable = false)
  public String status = STATUS_ACTIVE;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "valid_from", nullable = false)
  public Instant validFrom = Instant.now();

  @Column(name = "valid_to")
  public Instant validTo;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
