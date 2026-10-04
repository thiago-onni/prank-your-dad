package br.gov.sus.nexus.core.regulation.api;

import br.gov.sus.nexus.core.identity.api.IdentifierSystem;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * Registro de solicitação vindo do sistema oficial de regulação (OpenAPI {@code
 * RegulationRequestRegistration}). A justificativa clínica NUNCA trafega: só {@code
 * justification_present}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RegulationRequestRegistration(
    @NotNull @Valid SourceRef source,
    @NotNull @Valid CitizenRef citizenRef,
    @NotNull RegulationKind kind,
    @NotNull RegulationStatus status,
    RegulationPriority priority,
    @NotNull OffsetDateTime requestedAt,
    @NotBlank String requestedServiceCode,
    String codeSystem,
    String specialty,
    String requestingCnes,
    String requestingProfessionalId,
    String requestingProfessionalCbo,
    Boolean justificationPresent,
    @Min(0) @Max(1000) Integer attachedDocumentsCount,
    String cidCode,
    String providerCnes,
    OffsetDateTime scheduledAt,
    String regulatorId,
    @Size(max = 500) String decisionReason,
    OffsetDateTime occurredAt) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SourceRef(
      @NotBlank String system,
      @NotBlank String connector,
      @NotBlank String sourceRecordId,
      String sourceRecordVersion,
      String cnes) {}

  /** {@code municipal_citizen_id} OU identificador de origem para resolução via MPI. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CitizenRef(
      String municipalCitizenId, IdentifierSystem identifierSystem, String identifierValue) {}
}
