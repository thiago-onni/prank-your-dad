package br.gov.sus.nexus.connectors.sdk.ledger;

/** Estados de {@code integration_message} (OpenAPI {@code IntegrationMessageStatus}). */
public enum IntegrationMessageStatus {
  RECEIVED,
  TRANSFORMED,
  VALIDATED,
  PUBLISHED,
  PROCESSED,
  FAILED,
  DEAD_LETTERED,
  REPROCESSING;

  public String apiValue() {
    return name().toLowerCase();
  }
}
