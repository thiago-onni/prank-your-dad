package br.gov.sus.nexus.core.production.api;

import br.gov.sus.nexus.core.identity.api.IdentifierSystem;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Registro de produção vindo de fonte (OpenAPI {@code ProductionRecordRegistration}). CNS do
 * profissional e CNS/CPF do cidadão são aceitos em claro apenas na entrada: o core persiste hash
 * (HMAC por tenant), máscara e cifra (para a exportação) — nunca o valor em claro.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionRecordRegistration(
    @NotNull @Valid ProductionSource source,
    @NotNull ProductionKind kind,
    @NotBlank @Pattern(regexp = "^[0-9]{4}(0[1-9]|1[0-2])$") String competence,
    @NotBlank @Pattern(regexp = "^[0-9]{7}$") String cnes,
    String professionalCns,
    @NotBlank @Pattern(regexp = "^[0-9]{6}$") String professionalCbo,
    @NotBlank @Pattern(regexp = "^[0-9]{10}$") String procedureCode,
    @NotNull @Min(1) @Max(999999) Integer quantity,
    @Valid CitizenRef citizenRef,
    String cidCode,
    @NotNull LocalDate attendanceDate,
    @Pattern(regexp = "elective|urgency|work_accident|other") String characterOfCare,
    @Size(max = 13) String apacNumber,
    @Size(max = 13) String aihNumber,
    ExternalRef encounterRef,
    ExternalRef appointmentRef,
    ExternalRef hospitalEpisodeRef) {

  /** {@code municipal_citizen_id} OU identificador (CNS/CPF) para resolução via MPI. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CitizenRef(
      String municipalCitizenId, IdentifierSystem identifierSystem, String identifierValue) {

    public boolean present() {
      return (municipalCitizenId != null && !municipalCitizenId.isBlank())
          || (identifierValue != null && !identifierValue.isBlank());
    }
  }
}
