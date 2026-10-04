package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Correção humana com justificativa (PRO-006; OpenAPI {@code ProductionCorrection}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionCorrection(
    @NotBlank @Size(min = 10, max = 1000) String justification,
    @NotNull @Valid Changes changes,
    List<String> waiveIssueIds) {

  /** Campos corrigidos; nulos permanecem como estão. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Changes(
      @Pattern(regexp = "^[0-9]{7}$") String cnes,
      @Pattern(regexp = "^[0-9]{6}$") String professionalCbo,
      String professionalCns,
      @Pattern(regexp = "^[0-9]{10}$") String procedureCode,
      @Min(1) Integer quantity,
      String cidCode,
      LocalDate attendanceDate,
      @Pattern(regexp = "^[0-9]{4}(0[1-9]|1[0-2])$") String competence,
      @Pattern(regexp = "elective|urgency|work_accident|other") String characterOfCare,
      String apacNumber,
      String aihNumber,
      @Valid ProductionRecordRegistration.CitizenRef citizenRef,
      ExternalRef encounterRef,
      ExternalRef appointmentRef,
      ExternalRef hospitalEpisodeRef) {}
}
