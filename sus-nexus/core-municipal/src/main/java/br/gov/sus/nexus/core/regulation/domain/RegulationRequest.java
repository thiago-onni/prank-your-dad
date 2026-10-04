package br.gov.sus.nexus.core.regulation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** Solicitação regulatória espelhada do sistema oficial ({@code regulation.regulation_request}). */
@Entity
@Table(schema = "regulation", name = "regulation_request")
public class RegulationRequest {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "citizen_id", nullable = false)
  public String citizenId;

  @Column(nullable = false)
  public String kind;

  @Column(nullable = false)
  public String status;

  @Column(nullable = false)
  public String priority;

  @Column(name = "requested_at", nullable = false)
  public Instant requestedAt;

  @Column(name = "requested_service_code", nullable = false)
  public String requestedServiceCode;

  @Column(name = "code_system", nullable = false)
  public String codeSystem;

  public String specialty;

  @Column(name = "requesting_cnes")
  public String requestingCnes;

  @Column(name = "requesting_professional_id")
  public String requestingProfessionalId;

  @Column(name = "requesting_professional_cbo")
  public String requestingProfessionalCbo;

  @Column(name = "justification_present")
  public Boolean justificationPresent;

  @Column(name = "attached_documents_count")
  public Integer attachedDocumentsCount;

  @Column(name = "provider_cnes")
  public String providerCnes;

  @Column(name = "scheduled_at")
  public Instant scheduledAt;

  @Column(name = "appointment_id")
  public String appointmentId;

  @Column(name = "regulator_id")
  public String regulatorId;

  @Column(name = "decision_reason")
  public String decisionReason;

  @Column(name = "sla_due_at")
  public Instant slaDueAt;

  @Column(name = "sla_policy_id")
  public String slaPolicyId;

  @Column(name = "sla_breached", nullable = false)
  public boolean slaBreached;

  @Column(name = "decided_at")
  public Instant decidedAt;

  @Column(name = "source_system", nullable = false)
  public String sourceSystem;

  @Column(name = "source_record_id", nullable = false)
  public String sourceRecordId;

  @Column(name = "source_record_version")
  public String sourceRecordVersion;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();

  @Version public long version;
}
