package br.gov.sus.nexus.core.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Evidência explicável de um par avaliado (atributo, comparação, concordância, peso). */
@Entity
@Table(schema = "identity", name = "citizen_match_evidence")
public class CitizenMatchEvidence {

  @Id public String id;

  @Column(name = "tenant_id", nullable = false)
  public String tenantId;

  @Column(name = "match_candidate_id", nullable = false)
  public String matchCandidateId;

  @Column(nullable = false)
  public String attribute;

  @Column(nullable = false)
  public String comparison;

  @Column(nullable = false)
  public String agreement;

  @Column(nullable = false)
  public double weight;
}
