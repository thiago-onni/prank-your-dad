package br.gov.sus.nexus.core.integration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Erro registrado para uma mensagem (histórico; last_error fica na mensagem). */
@Entity
@Table(schema = "integration", name = "error")
public class IntegrationError {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "message_id", nullable = false)
  public String messageId;

  @Column(name = "connector_id", nullable = false)
  public String connectorId;

  public String stage;
  public String code;
  public String message;

  @Column(nullable = false)
  public int attempt = 1;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt = Instant.now();
}
