package br.gov.sus.nexus.fhir.security;

import jakarta.enterprise.context.RequestScoped;

/** Contexto da requisição corrente (identidade, tenant, finalidade, correlação). */
@RequestScoped
public class RequestContext {

  private Identity identity = Identity.anonymous();
  private String purposeOfUse;
  private String correlationId;

  public Identity identity() {
    return identity;
  }

  public void setIdentity(Identity identity) {
    this.identity = identity;
  }

  public String tenantId() {
    return identity.tenantId();
  }

  public String purposeOfUse() {
    return purposeOfUse;
  }

  public void setPurposeOfUse(String purposeOfUse) {
    this.purposeOfUse = purposeOfUse;
  }

  public String correlationId() {
    return correlationId;
  }

  public void setCorrelationId(String correlationId) {
    this.correlationId = correlationId;
  }
}
