package br.gov.sus.nexus.fhir.security;

import jakarta.ws.rs.container.ContainerRequestContext;

/** Resolve a {@link Identity} da requisição (OIDC em produção, headers em dev/test). */
public interface IdentityResolver {

  Identity resolve(ContainerRequestContext request);
}
