package br.gov.sus.nexus.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Par (registro de entrada × candidato) avaliado pelo MPI, com método/score/classificação. */
@Entity
@Table(schema = "identity", name = "citizen_match_candidate")
public class CitizenMatchCandidate {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "case_id")
  public String caseId;

  @Column(name = "incoming_citizen_id", nullable = false)
  public String incomingCitizenId;

  @Column(name = "candidate_citizen_id", nullable = false)
  public String candidateCitizenId;

  @Column(nullable = false)
  public String method;

  @Column(nullable = false)
  public String classification;

  public Double score;

  @Column(name = "rule_version")
  public String ruleVersion;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();
}
