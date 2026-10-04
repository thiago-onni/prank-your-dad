package br.gov.sus.nexus.core.journey.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;

/** Resumo operacional do cidadão (JOR-008; OpenAPI {@code CitizenOperationalSummary}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CitizenOperationalSummary(
    String citizenId,
    OffsetDateTime lastApsEncounterAt,
    OffsetDateTime nextAppointmentAt,
    long openTasks,
    long openRegulationRequests,
    long pendingExams,
    OffsetDateTime lastHospitalDischargeAt,
    List<String> careLines,
    long careGaps,
    Boolean contactValid) {}
