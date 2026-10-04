package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/**
 * Forma canônica do cidadão vinda do core municipal ({@code CitizenSummary}/{@code CitizenDetail}
 * em {@code contracts/openapi/core-municipal.yaml}). Identificadores podem vir mascarados ({@code
 * value_masked}) ou em claro ({@code value}, apenas pelo canal interno).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalCitizen(
    String id,
    Integer version,
    String displayName,
    String legalName,
    String socialName,
    String motherName,
    String motherNameMasked,
    String birthdate,
    String sex,
    Boolean deceased,
    String registrationState,
    List<CanonicalIdentifier> identifiers,
    String healthUnitCnes,
    String teamIne,
    String microarea,
    CanonicalAddress address,
    List<CanonicalContact> contacts,
    Instant updatedAt) {

  /** Identificador canônico (mascarado ou em claro). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CanonicalIdentifier(
      String id,
      String system,
      String value,
      String valueMasked,
      String status,
      String sourceSystem) {}

  /** Endereço canônico. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CanonicalAddress(
      String street,
      String number,
      String complement,
      String district,
      String cityIbge,
      String postalCode) {}

  /** Contato canônico (mascarado ou em claro). */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CanonicalContact(
      String kind, String value, String valueMasked, Boolean preferred) {}
}
