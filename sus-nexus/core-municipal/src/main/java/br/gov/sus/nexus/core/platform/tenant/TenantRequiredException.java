package br.gov.sus.nexus.core.platform.tenant;

import br.gov.sus.nexus.core.platform.errors.ProblemException;

/** Lançada quando uma operação exige tenant e ele não foi resolvido. Mapeada para HTTP 400. */
public class TenantRequiredException extends ProblemException {

  public TenantRequiredException(String detail) {
    super(400, "Tenant obrigatório", detail, "urn:sus-nexus:problem:tenant-required");
  }
}
