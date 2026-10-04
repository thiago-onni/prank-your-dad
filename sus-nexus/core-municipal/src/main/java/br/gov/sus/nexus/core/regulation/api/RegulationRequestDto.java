package br.gov.sus.nexus.core.regulation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;

/** Solicitação regulatória (OpenAPI {@code RegulationRequest}). Sem CID nem justificativa. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegulationRequestDto(
    String id,
    String citizenId,
    RegulationKind kind,
    RegulationStatus status,
    RegulationPriority priority,
    OffsetDateTime requestedAt,
    String requestedServiceCode,
    String codeSystem,
    String serviceDescription,
    String specialty,
    String requestingCnes,
    String requestingUnitName,
    String requestingProfessionalId,
    String providerCnes,
    String providerName,
    OffsetDateTime scheduledAt,
    String appointmentId,
    String regulatorId,
    String decisionReason,
    Boolean justificationPresent,
    Integer attachedDocumentsCount,
    long waitingDays,
    OffsetDateTime slaDueAt,
    boolean slaBreached,
    List<RegulationIssueDto> issues,
    List<StatusEntry> statusHistory,
    String sourceSystem,
    String sourceRecordId,
    long version) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StatusEntry(
      RegulationStatus status, OffsetDateTime occurredAt, String actor, String reason) {}
}
