package br.gov.sus.nexus.core.identity.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Detalhe do cidadão (OpenAPI {@code CitizenDetail} = CitizenSummary + campos adicionais). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CitizenDetail(
    String id,
    String displayName,
    LocalDate birthdate,
    Sex sex,
    String motherNameMasked,
    RegistrationState registrationState,
    List<MaskedIdentifier> identifiers,
    String healthUnitCnes,
    String teamIne,
    String microarea,
    IdentityConfidence identityConfidence,
    long version,
    String legalName,
    String socialName,
    String motherName,
    Address address,
    List<Contact> contacts,
    Map<String, Provenance> attributeProvenance,
    List<DataQualityIssue> dataQualityIssues,
    String mergedIntoId) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Address(
      String street,
      String number,
      String complement,
      String district,
      String cityIbge,
      String postalCode) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Contact(String kind, String valueMasked, boolean preferred) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Provenance(
      String sourceSystem, String sourceRecordId, OffsetDateTime receivedAt, Double confidence) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DataQualityIssue(String rule, String field, String message) {}
}
