package br.gov.sus.nexus.core.exams.api;

import br.gov.sus.nexus.core.identity.api.IdentifierSystem;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

/**
 * Registro de pedido de exame vindo de um sistema de origem (OpenAPI {@code
 * ExamOrderRegistration}).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExamOrderRegistration(
    @NotNull @Valid SourceRef source,
    @NotNull @Valid CitizenRef citizenRef,
    @NotNull ExamOrderStatus status,
    @NotNull OffsetDateTime requestedAt,
    @NotBlank String examCode,
    String codeSystem,
    String examDescription,
    @Pattern(regexp = "laboratory|imaging|other") String category,
    String requestingCnes,
    String requestingProfessionalId,
    String regulationSourceRecordId,
    String appointmentSourceRecordId,
    String careLine,
    @Pattern(regexp = "routine|priority|urgent") String priority,
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
