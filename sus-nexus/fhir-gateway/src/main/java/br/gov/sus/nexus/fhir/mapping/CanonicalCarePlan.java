package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/** Canônico {@code CarePlan} de {@code contracts/openapi/core-municipal.yaml}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalCarePlan(
    String id,
    String citizenId,
    String careLine,
    String status,
    String protocolId,
    String protocolVersion,
    String healthUnitCnes,
    String teamIne,
    String responsibleProfessionalId,
    Origin origin,
    List<Item> items,
    Integer openGaps,
    Instant createdAt,
    Instant updatedAt,
    String closedReason,
    Integer version) {

  /** Origem do plano. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Origin(String kind, String id) {}

  /** {@code CarePlanItem}. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Item(
      String id,
      String kind,
      String title,
      String code,
      String codeSystem,
      Instant expectedBy,
      Integer periodicityDays,
      String status,
      Instant performedAt,
      String evidenceRef,
      Boolean overdue) {}
}
