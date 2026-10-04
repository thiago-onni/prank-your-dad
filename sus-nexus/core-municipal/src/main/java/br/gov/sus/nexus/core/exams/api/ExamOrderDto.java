package br.gov.sus.nexus.core.exams.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;

/** Pedido de exame (OpenAPI {@code ExamOrder}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExamOrderDto(
    String id,
    String citizenId,
    ExamOrderStatus status,
    OffsetDateTime requestedAt,
    String examCode,
    String codeSystem,
    String examDescription,
    String category,
    String priority,
    String requestingCnes,
    String requestingUnitName,
    String requestingProfessionalId,
    String performerCnes,
    String regulationRequestId,
    String appointmentId,
    OffsetDateTime scheduledAt,
    OffsetDateTime performedAt,
    OffsetDateTime reportedAt,
    String careLine,
    List<ExamIssue> issues,
    List<ExamResultDto> results,
    List<StatusEntry> statusHistory,
    CycleTimes cycleTimes,
    String sourceSystem,
    String sourceRecordId,
    long version) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StatusEntry(ExamOrderStatus status, OffsetDateTime occurredAt, String reason) {}

  /** Tempos de ciclo em horas (EXA-010). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CycleTimes(
      Double requestToSchedule,
      Double scheduleToPerform,
      Double performToReport,
      Double reportToFollowup) {}
}
