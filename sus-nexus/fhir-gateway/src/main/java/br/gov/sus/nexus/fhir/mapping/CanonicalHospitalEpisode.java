package br.gov.sus.nexus.fhir.mapping;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/** Canônico {@code HospitalEpisode} de {@code contracts/openapi/core-municipal.yaml}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalHospitalEpisode(
    String id,
    String citizenId,
    String hospitalCnes,
    String hospitalName,
    String episodeClass,
    String status,
    Instant admittedAt,
    Instant dischargedAt,
    Integer lengthOfStayDays,
    String disposition,
    String ward,
    String bed,
    String admissionSource,
    String regulationRequestId,
    String principalDiagnosisCid,
    String aihNumber,
    Boolean readmissionWithin30d,
    String previousEpisodeId,
    String referenceHealthUnitCnes,
    String referenceTeamIne,
    String riskLevel,
    String riskRuleVersion,
    Followup followup,
    Boolean hasSummaryDocument,
    List<Movement> movements,
    String sourceSystem,
    String sourceRecordId,
    Integer version,
    String sensitivity) {

  /** Seguimento pós-alta. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Followup(
      String status,
      String taskId,
      Instant dueAt,
      String outcome,
      Instant contactedAt,
      String carePlanId) {}

  /** Movimentação. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Movement(String movement, Instant occurredAt, String ward, String bed) {}
}
