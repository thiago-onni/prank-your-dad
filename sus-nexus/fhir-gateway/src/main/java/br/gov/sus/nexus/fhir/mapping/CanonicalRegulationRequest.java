package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/** Canônico {@code RegulationRequest} de {@code contracts/openapi/core-municipal.yaml}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalRegulationRequest(
    String id,
    String citizenId,
    String kind,
    String status,
    String priority,
    Instant requestedAt,
    String requestedServiceCode,
    String codeSystem,
    String serviceDescription,
    String specialty,
    String requestingCnes,
    String requestingUnitName,
    String requestingProfessionalId,
    String providerCnes,
    String providerName,
    Instant scheduledAt,
    String appointmentId,
    String regulatorId,
    String decisionReason,
    Boolean justificationPresent,
    Integer attachedDocumentsCount,
    Integer waitingDays,
    Instant slaDueAt,
    Boolean slaBreached,
    List<Issue> issues,
    String sourceSystem,
    String sourceRecordId,
    Integer version) {

  /** Pendência da solicitação. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Issue(String id, String kind, String status, Instant createdAt) {}
}
