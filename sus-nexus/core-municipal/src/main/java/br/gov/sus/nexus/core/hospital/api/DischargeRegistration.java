package br.gov.sus.nexus.core.hospital.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.util.List;

/** Alta / sumário de alta — só metadados e referência segura (OpenAPI {@code DischargeRegistration}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DischargeRegistration(
    @NotNull @Valid SourceRef source,
    @NotNull OffsetDateTime dischargedAt,
    @NotBlank @Pattern(regexp = "home|home_with_care|transfer|against_advice|deceased|other")
        String disposition,
    String principalDiagnosisCid,
    @Min(0) Integer proceduresCount,
    Boolean followupPlanPresent,
    @Min(0) Integer followupDueDays,
    List<String> careLines,
    String summaryDocumentRef,
    String summaryDocumentSha256) {}
