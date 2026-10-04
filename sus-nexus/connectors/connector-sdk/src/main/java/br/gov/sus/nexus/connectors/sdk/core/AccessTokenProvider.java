package br.gov.sus.nexus.connectors.sdk.core;

/** Fornece o bearer token usado nas chamadas ao core. */
public interface AccessTokenProvider {
  String accessToken();
}
