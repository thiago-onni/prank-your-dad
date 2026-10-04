package br.gov.sus.nexus.fhir.directwrite;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * {@code CitizenRegistration} de {@code contracts/openapi/core-municipal.yaml}: porta única de
 * entrada de cidadãos no core (resolve identidade no MPI).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CitizenRegistration(
    Source source,
    List<Identifier> identifiers,
    Demographics demographics,
    Address address,
    List<Contact> contacts,
    Territory territory) {

  /** Origem do registro. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Source(
      String system,
      String connector,
      String sourceRecordId,
      String sourceRecordVersion,
      String cnes) {}

  /** Identificador ({@code system} em IdentifierSystem: CNS, CPF, …). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Identifier(String system, String value) {}

  /** Dados demográficos. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Demographics(
      String legalName,
      String socialName,
      String motherName,
      String birthdate,
      String sex,
      Boolean deceased,
      String deceasedAt) {}

  /** Endereço. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Address(
      String street,
      String number,
      String complement,
      String district,
      String cityIbge,
      String postalCode) {}

  /** Contato ({@code kind}: phone, mobile, email). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Contact(String kind, String value) {}

  /** Território. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Territory(String healthUnitCnes, String teamIne, String microarea) {}
}
