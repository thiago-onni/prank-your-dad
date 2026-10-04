package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/** Registro de cidadão vindo de um sistema de origem (OpenAPI {@code CitizenRegistration}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CitizenRegistration(
    @NotNull @Valid SourceRef source,
    List<@Valid IdentifierInput> identifiers,
    @NotNull @Valid Demographics demographics,
    AddressInput address,
    List<@Valid ContactInput> contacts,
    TerritoryInput territory) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SourceRef(
      @NotBlank String system,
      @NotBlank String connector,
      @NotBlank String sourceRecordId,
      String sourceRecordVersion,
      String cnes) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record IdentifierInput(@NotNull IdentifierSystem system, @NotBlank String value) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Demographics(
      @NotBlank String legalName,
      String socialName,
      String motherName,
      String fatherName,
      @NotNull LocalDate birthdate,
      Sex sex,
      String raceColor,
      String nationality,
      Boolean deceased,
      LocalDate deceasedAt) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AddressInput(
      String street,
      String number,
      String complement,
      String district,
      String cityIbge,
      String postalCode) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ContactInput(@NotBlank String kind, @NotBlank String value) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record TerritoryInput(String healthUnitCnes, String teamIne, String microarea) {}
}
