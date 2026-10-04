package br.gov.sus.nexus.connectors.sdk.api;

import java.time.Instant;

/** Resultado da autenticação na fonte. */
public record AuthResult(
    boolean authenticated, String principal, Instant expiresAt, String detail) {

  public static AuthResult ok(String principal) {
    return new AuthResult(true, principal, null, null);
  }

  public static AuthResult notRequired() {
    return new AuthResult(true, "anonymous", null, "fonte sem autenticação");
  }

  public static AuthResult failed(String detail) {
    return new AuthResult(false, null, null, detail);
  }
}
