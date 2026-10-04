package br.gov.sus.nexus.core.hospital.api;

import br.gov.sus.nexus.core.identity.api.IdentifierSystem;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Movimentação ADT vinda do HIS (OpenAPI {@code HospitalMovementRegistration}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HospitalMovementRegistration(
    @NotNull @Valid SourceRef source,
    @NotNull @Valid CitizenRef citizenRef,
    @NotBlank String hospitalCnes,
    @NotBlank @Pattern(regexp = "inpatient|emergency|observation|day_hospital") String episodeClass,
    @NotBlank @Pattern(regexp = "admit|transfer|bed_change|discharge|death|cancel") String movement,
    @NotNull OffsetDateTime occurredAt,
    String ward,
    String bed,
    String attendingProfessionalId,
    @Pattern(regexp = "emergency|regulation|transfer|elective|other") String admissionSource,
    String regulationSourceRecordId,
    String principalDiagnosisCid,
    String aihNumber,
    @Size(max = 500) String reason) {

  /** {@code municipal_citizen_id} OU identificador de origem para resolução via MPI. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CitizenRef(
      String municipalCitizenId, IdentifierSystem identifierSystem, String identifierValue) {}
}
