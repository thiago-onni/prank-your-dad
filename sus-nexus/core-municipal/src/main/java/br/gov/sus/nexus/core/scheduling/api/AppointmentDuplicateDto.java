package br.gov.sus.nexus.core.scheduling.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;

/** Duplicidade detectada (OpenAPI {@code AppointmentDuplicate}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AppointmentDuplicateDto(
    String id,
    String citizenId,
    String serviceCode,
    List<AppointmentDto> appointments,
    int windowHours,
    OffsetDateTime detectedAt) {}
