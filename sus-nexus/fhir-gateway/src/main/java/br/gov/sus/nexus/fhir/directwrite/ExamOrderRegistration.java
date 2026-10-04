package br.gov.sus.nexus.fhir.directwrite;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;

/** {@code ExamOrderRegistration} de {@code contracts/openapi/core-municipal.yaml}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExamOrderRegistration(
    CitizenRegistration.Source source,
    CitizenRef citizenRef,
    String status,
    Instant requestedAt,
    String examCode,
    String codeSystem,
    String examDescription,
    String category,
    String requestingCnes,
    String requestingProfessionalId,
    String regulationSourceRecordId,
    String careLine,
    String priority,
    Instant occurredAt) {

  /** {@code municipal_citizen_id} ou identificador de origem para resolução. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CitizenRef(
      String municipalCitizenId, String identifierSystem, String identifierValue) {}
}
