package br.gov.sus.nexus.core.hospital.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;

/** Episódio hospitalar (OpenAPI {@code HospitalEpisode}). CID só quando a política permitir. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HospitalEpisodeDto(
    String id,
    String citizenId,
    String hospitalCnes,
    String hospitalName,
    String episodeClass,
    HospitalEpisodeStatus status,
    OffsetDateTime admittedAt,
    OffsetDateTime dischargedAt,
    Integer lengthOfStayDays,
    String disposition,
    String ward,
    String bed,
    String admissionSource,
    String regulationRequestId,
    String principalDiagnosisCid,
    String aihNumber,
    boolean readmissionWithin30d,
    String previousEpisodeId,
    String referenceHealthUnitCnes,
    String referenceTeamIne,
    String riskLevel,
    String riskRuleVersion,
    List<String> careLines,
    Followup followup,
    CounterReferral counterReferral,
    boolean hasSummaryDocument,
    List<Movement> movements,
    String sourceSystem,
    String sourceRecordId,
    long version) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Followup(
      String status,
      String taskId,
      OffsetDateTime dueAt,
      String outcome,
      OffsetDateTime contactedAt,
      String carePlanId) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CounterReferral(
      OffsetDateTime receivedAt, boolean hasDocument, Integer recommendationsCount) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Movement(String movement, OffsetDateTime occurredAt, String ward, String bed) {}

  public HospitalEpisodeDto withoutCid() {
    return new HospitalEpisodeDto(
        id, citizenId, hospitalCnes, hospitalName, episodeClass, status, admittedAt, dischargedAt,
        lengthOfStayDays, disposition, ward, bed, admissionSource, regulationRequestId, null,
        aihNumber, readmissionWithin30d, previousEpisodeId, referenceHealthUnitCnes,
        referenceTeamIne, riskLevel, riskRuleVersion, careLines, followup, counterReferral,
        hasSummaryDocument, movements, sourceSystem, sourceRecordId, version);
  }
}
