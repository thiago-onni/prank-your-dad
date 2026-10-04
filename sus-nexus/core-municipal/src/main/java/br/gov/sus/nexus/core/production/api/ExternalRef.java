package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Referência a registro de outro domínio pelo vínculo de origem (OpenAPI {@code ExternalRef}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExternalRef(String system, String sourceRecordId) {

  public boolean present() {
    return sourceRecordId != null && !sourceRecordId.isBlank();
  }
}
