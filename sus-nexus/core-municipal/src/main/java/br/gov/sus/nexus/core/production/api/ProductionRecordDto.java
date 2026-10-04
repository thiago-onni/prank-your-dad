package br.gov.sus.nexus.core.production.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Registro de produção (OpenAPI {@code ProductionRecord}). CNS/CPF só mascarados. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionRecordDto(
    String id,
    ProductionKind kind,
    String competence,
    String cnes,
    String healthUnitName,
    String professionalCnsMasked,
    String professionalCbo,
    String procedureCode,
    String procedureDisplay,
    int quantity,
    String citizenId,
    String citizenIdentifierMasked,
    String cidCode,
    LocalDate attendanceDate,
    String characterOfCare,
    String apacNumber,
    String aihNumber,
    String appointmentId,
    String hospitalEpisodeId,
    ExternalRef encounterRef,
    ProductionRecordStatus status,
    String ruleVersion,
    OffsetDateTime validatedAt,
    BigDecimal unitValue,
    BigDecimal estimatedValue,
    BigDecimal paidAmount,
    Integer approvedQuantity,
    String outcomeReasonCode,
    String outcomeReason,
    String batchId,
    OffsetDateTime deadlineAt,
    int correctionCount,
    List<ProductionIssueDto> issues,
    List<HistoryEntry> history,
    String sourceSystem,
    String sourceRecordId,
    long version) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record HistoryEntry(
      String action,
      String fromStatus,
      String toStatus,
      String actorId,
      String justification,
      String ruleVersion,
      OffsetDateTime occurredAt) {}
}
