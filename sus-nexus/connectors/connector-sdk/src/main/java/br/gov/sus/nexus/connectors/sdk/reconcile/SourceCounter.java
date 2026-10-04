package br.gov.sus.nexus.connectors.sdk.reconcile;

import br.gov.sus.nexus.connectors.sdk.api.Period;

/** Conta registros na fonte por entidade e período (implementado por cada conector). */
public interface SourceCounter {
  long count(String entityType, Period period);
}
