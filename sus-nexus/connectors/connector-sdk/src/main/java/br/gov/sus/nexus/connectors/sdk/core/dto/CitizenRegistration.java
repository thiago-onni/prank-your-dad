package br.gov.sus.nexus.connectors.sdk.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** Payload de {@code POST /api/v1/citizens} (OpenAPI {@code CitizenRegistration}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CitizenRegistration(
    SourceRef source,
    List<Identifier> identifiers,
    Demographics demographics,
    Address address,
    List<Contact> contacts,
    Territory territory) {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Identifier(String system, String value) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Demographics(
      String legalName,
      String socialName,
      String motherName,
      String fatherName,
      String birthdate,
      String sex,
      String raceColor,
      String nationality,
      Boolean deceased,
      String deceasedAt) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Address(
      String street,
      String number,
      String complement,
      String district,
      String cityIbge,
      String postalCode) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Contact(String kind, String value) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Territory(String healthUnitCnes, String teamIne, String microarea) {}
}
